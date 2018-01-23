package de.tudarmstadt.ukp.inception.conceptlinking.service;

import static org.apache.uima.fit.factory.AnalysisEngineFactory.createEngineDescription;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.Resource;

import org.apache.commons.text.similarity.LevenshteinDistance;

import org.apache.uima.UIMAException;
import org.apache.uima.analysis_engine.AnalysisEngine;
import org.apache.uima.analysis_engine.AnalysisEngineDescription;
import org.apache.uima.fit.factory.AnalysisEngineFactory;
import org.apache.uima.fit.factory.JCasFactory;
import org.apache.uima.fit.util.JCasUtil;
import org.apache.uima.jcas.JCas;
import org.apache.uima.resource.ResourceInitializationException;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sparql.SPARQLRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import de.tudarmstadt.ukp.clarin.webanno.api.DocumentService;
import de.tudarmstadt.ukp.clarin.webanno.api.annotation.event.DocumentOpenedEvent;
import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationDocument;
import de.tudarmstadt.ukp.clarin.webanno.security.UserDao;
import de.tudarmstadt.ukp.clarin.webanno.security.model.User;
import de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Sentence;
import de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Token;
import de.tudarmstadt.ukp.dkpro.core.corenlp.CoreNlpPosTagger;
import de.tudarmstadt.ukp.dkpro.core.corenlp.CoreNlpSegmenter;
import de.tudarmstadt.ukp.dkpro.core.performance.Stopwatch;
import de.tudarmstadt.ukp.dkpro.core.stanfordnlp.StanfordPosTagger;
import de.tudarmstadt.ukp.dkpro.core.stanfordnlp.StanfordSegmenter;
import edu.stanford.nlp.util.StringUtils;
import de.tudarmstadt.ukp.inception.conceptlinking.model.Entity;
import de.tudarmstadt.ukp.inception.conceptlinking.model.SemanticSignature;
import de.tudarmstadt.ukp.inception.conceptlinking.util.QueryUtil;
import de.tudarmstadt.ukp.inception.conceptlinking.util.Utils;

import opennlp.tools.sentdetect.SentenceDetectorME;
import opennlp.tools.sentdetect.SentenceModel;

public class ConceptLinkingService
{

    private static Logger logger = LoggerFactory.getLogger(ConceptLinkingService.class);

    private static RepositoryConnection conn;

    private static AnalysisEngine pipeline;
    private static AnalysisEngine coreNlpPipeline;

    private static SentenceDetectorME detector;
    
    private static JCas mentionSentence;
    
    private static String wd = "";
    // private static String wd = "workspace/inception-concept-linking/";
    
    private static final String[] PUNCTUATION_VALUES 
            = new String[] { "``", "''", "(", ")", ",", ".", ":", "--" };

    private static final Set<String> punctuations = new HashSet<>(
            Arrays.asList(PUNCTUATION_VALUES));

    private static final Set<String> stopwords 
            = Utils.readFile(wd + "resources/stopwords-de.txt");

    private static int candidateQueryLimit = 100;
    private static int signatureQueryLimit = 10;
    
    private static final Map<String, Integer> entityFrequencyMap
            = Utils.loadEntityFrequencyMap(wd + "resources/wikidata_entity_freqs.map");
    
    public static void init(String SPARQL_ENDPOINT)
    {
        initializeConnection(SPARQL_ENDPOINT);
    
        AnalysisEngineDescription desc;
        try {
            desc = createEngineDescription(
                    createEngineDescription(Stopwatch.class, 
                            Stopwatch.PARAM_TIMER_NAME, "stanfordNlpTimer"),
                    createEngineDescription(StanfordSegmenter.class),
                    createEngineDescription(StanfordPosTagger.class,
                            StanfordSegmenter.PARAM_LANGUAGE_FALLBACK, "en"),
                    createEngineDescription(
                            Stopwatch.class,
                            Stopwatch.PARAM_TIMER_NAME, "stanfordNlpTimer",
                            Stopwatch.PARAM_OUTPUT_FILE, "target/stanfordnlpPipeline.txt"
                    ));
            ConceptLinkingService.pipeline = AnalysisEngineFactory.createEngine(desc);
            
            desc = createEngineDescription(
                    createEngineDescription(Stopwatch.class, 
                            Stopwatch.PARAM_TIMER_NAME, "coreNlpTimer"),
                    createEngineDescription(CoreNlpSegmenter.class),
                    createEngineDescription(CoreNlpPosTagger.class,
                            CoreNlpSegmenter.PARAM_LANGUAGE, "en"),
                    createEngineDescription(
                            Stopwatch.class,
                            Stopwatch.PARAM_TIMER_NAME, "coreNlpTimer",
                            Stopwatch.PARAM_OUTPUT_FILE, "target/coreNlpPipeline.txt"
                    ));
            coreNlpPipeline = AnalysisEngineFactory.createEngine(desc);
        }
        catch (ResourceInitializationException e) {
            logger.error("Could not create AnalysisEngine!", e);
        }
        
        try {
            mentionSentence = JCasFactory.createText("", "en");
        }
        catch (UIMAException e) {
            logger.error("Could not create JCas.", e);
        }
        
        try {
            //Loading german sentence detector model from OpenNlp 
            InputStream inputStream = 
                    new FileInputStream(wd + "resources/de-sent.bin");
            SentenceModel model = new SentenceModel(inputStream); 
            
            //Instantiating the SentenceDetectorME class 
            ConceptLinkingService.detector = new SentenceDetectorME(model);
        }
        catch (IOException e) {
            logger.error("Could not load sentence detector model.", e);
        } 
    
    }
    
    public static void initializeConnection(String SPARQL_ENDPOINT)
    {
        SPARQLRepository repo = new SPARQLRepository(SPARQL_ENDPOINT);
        repo.initialize();
        conn = repo.getConnection();
    }

    public static String mapWikipediaUrlToWikidataUrl(String url)
    {
        String wikidataQueryString = QueryUtil.mapWikipediaUrlToWikidataUrlQuery(url);
        TupleQuery wikidataIdQuery = conn.prepareTupleQuery(QueryLanguage.SPARQL,
                wikidataQueryString);
        try (TupleQueryResult wikidataIdResult = wikidataIdQuery.evaluate()) {
            while (wikidataIdResult.hasNext()) {
                BindingSet sol = wikidataIdResult.next();
                return sol.getValue("e2").toString();
            }
        }
        catch (Exception e) {
            logger.error("Could not map wikipedia URL to Wikidata Id", e);
        }
        return null;
    }

    /*
     * Retrieves the first sentence containing the mention as Tokens
     */
    public static List<Token> getMentionSentence(String docText, String mention)
        throws UIMAException, IOException
    {
        double startTime = System.currentTimeMillis();
        String sentenceText = findMentionSentenceInDoc(docText, mention);
        mentionSentence.reset();
        mentionSentence.setDocumentText(sentenceText);
        mentionSentence.setDocumentLanguage("en");
        
        pipeline.process(mentionSentence);
        pipeline.collectionProcessComplete();
        logger.debug(System.currentTimeMillis() - startTime + "ms for processing text.");

        for (Sentence s : JCasUtil.select(mentionSentence, Sentence.class)) {
            List<Token> sentence = new LinkedList<>();
            for (Token t : JCasUtil.selectCovered(Token.class, s)) {
                sentence.add(t);
            }
            return sentence;
        }
        logger.info("Could not return mention sentence.");
        return null;
    }

    public static List<Token> getMentionSentenceWithCorenlp(String docText, String mention) 
        throws UIMAException, IOException
    {
        double startTime = System.currentTimeMillis();
        String sentenceText = findMentionSentenceInDoc(docText, mention);
        mentionSentence.reset();
        mentionSentence.setDocumentText(sentenceText);
        mentionSentence.setDocumentLanguage("en");
        
        if (mentionSentence == null) {
            logger.info("Could not return mention sentence.");
            return null;
        }
        
        coreNlpPipeline.process(mentionSentence);
        logger.debug(System.currentTimeMillis() - startTime + "ms for processing text.");

        for (Sentence s : JCasUtil.select(mentionSentence, Sentence.class)) {
            List<Token> sentence = new LinkedList<>();
            for (Token t : JCasUtil.selectCovered(Token.class, s)) {
                sentence.add(t);
            }
            return sentence;
        }
        logger.info("Could not return mention sentence.");
        return null;
    }
    
    
    /**
     * Return sentence containing the mention
     * @param docText
     * @param mention
     * @return
     * @throws IOException
     */
    public static String findMentionSentenceInDoc(String docText, String mention) 
            throws IOException
    {    
        // Detecting the sentence
        String[] sentences = detector.sentDetect(docText);

        // Check whether mention occurs in this sentence
        for (String sent : sentences) {
            if (sent.contains(mention)) {
                return sent;
            }
        }
        logger.info("Mention " + mention + " could not be found in docText.");
        return null;
    }

    // TODO lemmatization
    public static Set<Entity> linkMention(String mention)
    {
        double startTime = System.currentTimeMillis();
        Set<Entity> linkings = new HashSet<>();
        List<String> mentionArray = Arrays.asList(mention.split(" "));

        ListIterator<String> it = mentionArray.listIterator();
        String current;
        while (it.hasNext()) {
            current = it.next();
            it.set(current);
            if (punctuations.contains(current)) {
                it.remove();
            }
        }

        boolean onlyStopwords = true;
        ListIterator<String> it2 = mentionArray.listIterator();
        while (it2.hasNext()) {
            current = it2.next();
            it2.set(current);
            if (!stopwords.contains(current)) {
                onlyStopwords = false;
                break;
            }
        }

        if (mentionArray.isEmpty() || onlyStopwords) {
            logger.error("Mention array is empty or consists of stopwords only - returning.");
            return null;
        }

        String entityQueryString = QueryUtil.entityQuery(mentionArray, candidateQueryLimit);
        TupleQuery query = conn.prepareTupleQuery(QueryLanguage.SPARQL, entityQueryString);
        try (TupleQueryResult entityResult = query.evaluate()) {
            while (entityResult.hasNext()) {
                BindingSet solution = entityResult.next();
                Value e2 = solution.getValue("e2");
                Value label = solution.getValue("label");
                Value anylabel = solution.getValue("anylabel");
                linkings.add(new Entity((e2 != null) ? e2.toString() : "",
                                     (label != null) ? label.toString() : "",
                                  (anylabel != null) ? anylabel.toString() : ""));
            }
        }
        catch (QueryEvaluationException e) {
            throw new QueryEvaluationException(e);
        }
        catch (NullPointerException e) {
            logger.error("NullPointerException occured:", e);
        }
        if (linkings.isEmpty()) {
            String[] split = mention.split(" ");
            if (split.length > 1) {
                for (String s : split) {
                    linkings.addAll(linkMention(s));
                }
            }
        }
        logger.debug(System.currentTimeMillis() - startTime + "ms for linkMention method.");
        return linkings;
    }

    /**
     * Finds the position of a mention in a given sentence and returns the corresponding tokens of
     * the mention with <mentionContextSize> tokens before and <mentionContextSize> after the
     * mention
     * 
     * TODO what happens if there are multiple mentions in a sentence?
     */
    public static List<Token> getMentionContext(List<Token> mentionSentence, List<String> mention,
            int mentionContextSize)
    {
        int start = 0, end = 0;
        int j = 0;
        boolean done = false;
        while (done == false && j < mentionSentence.size()) {
            for (int i = 0; i < mention.size(); i++) {
                if (!mentionSentence.get(j).getCoveredText()
                        .contains(mention.get(i))) {
                    break;
                }
                j++;
                if (i == mention.size() - 1) {
                    start = j - (mention.size() - 1) - mentionContextSize;
                    end = j + mentionContextSize + 1;
                    done = true;
                }
            }
            j++;
        }

        if (start == end) {
            logger.warn("Mention not found in sentence!");
            return mentionSentence;
        }
        if (start < 0) {
            start = 0;
        }
        if (end > mentionSentence.size()) {
            end = mentionSentence.size();
        }
        return mentionSentence.subList(start, end);
    }

    /**
     * The method should compute scores for each candidate linking for the given entity and sort the
     * candidates so that the most probable candidate comes first.
     *
     * @param mention
     * @param linkings
     * @param taggedText:
     *            the current text as a list of tagged token
     * @return
     * @throws UIMAException
     */
    public static List<Entity> computeCandidateScores(String mention, Set<Entity> linkings,
            String text)
        throws UIMAException, IOException
    {
        int mentionContextSize = 2;
        List<Token> mentionSentence = getMentionSentence(text, mention);
        if (mentionSentence == null) {
            return null;
        }
        List<String> splitMention = Arrays.asList(mention.split(" "));
        List<Token> mentionContext = getMentionContext(mentionSentence, splitMention,
                mentionContextSize);

        // TODO and t['ner'] not in {"ORDINAL", "MONEY", "TIME", "PERCENTAGE"}} \
        Set<String> sentenceContentTokens = new HashSet<>();
        for (Token t : mentionSentence) {
            if ((t.getPos().getPosValue().startsWith("V")
                    || t.getPos().getPosValue().startsWith("N")
                    || t.getPos().getPosValue().startsWith("J"))
                && !splitMention.contains(t.getCoveredText())
                && (!stopwords.contains(t.getCoveredText())
                    || !splitMention.contains(t.getCoveredText()))) {
                sentenceContentTokens.add(t.getCoveredText());
            }
        }

        double startLoop = System.currentTimeMillis();
        
        linkings.parallelStream().forEach( l -> {
            String wikidataId = l.getE2().replace("http://www.wikidata.org/entity/", "");
            String anylabel = l.getAnyLabel().toLowerCase();

            l.setIdRank(Math.log(Double.parseDouble(wikidataId.substring(1))));
            
            if (entityFrequencyMap.get(wikidataId) != null) {
                l.setFrequency(entityFrequencyMap.get(wikidataId));
            } else {
                l.setFrequency(0);
            }
            
            LevenshteinDistance lev = new LevenshteinDistance();
            l.setLevMatchLabel(lev.apply(mention, anylabel).intValue());
            l.setLevSentence(lev.apply(tokensToString(mentionContext), anylabel).intValue());
            l.setNumRelatedRelations(0);

            SemanticSignature sig = getSemanticSignature(wikidataId);
            Set<String> relatedEntities = sig.getRelatedEntities();
            Set<String> signatureOverlap = new HashSet<>();
            for (String s : relatedEntities) {
                if (sentenceContentTokens.contains(s)) {
                    signatureOverlap.add(s);
                }
            }
            l.setSignatureOverlapScore(splitMention.size() + signatureOverlap.size());
            l.setNumRelatedRelations(sig.getRelatedRelations().size());
        });
        List<Entity> result = sortCandidates(new ArrayList<>(linkings));
        logger.debug(System.currentTimeMillis() - startLoop + "ms until end loop.");
        return result;
    }

    private static List<Entity> sortCandidates(List<Entity> candidates)
    {
        Collections.sort(candidates, new Comparator<Entity>()
        {
            @Override
            public int compare(Entity e1, Entity e2)
            {
                return new org.apache.commons.lang.builder.CompareToBuilder()
                        .append(-e1.getSignatureOverlapScore(), -e2.getSignatureOverlapScore())
                        .append(e1.getLevSentence() + e1.getLevMatchLabel(),
                                e2.getLevSentence() + e2.getLevMatchLabel())
                        .append(-e1.getFrequency(), -e2.getFrequency())
                        .append(-e1.getNumRelatedRelations(), -e2.getNumRelatedRelations())
                        .append(e1.getIdRank(), e2.getIdRank()).toComparison();
            }
        });
        return candidates;
    }

    private static String tokensToString(List<Token> sentence)
    {
        StringBuilder builder = new StringBuilder();
        for (Token t : sentence) {
            builder.append(t.getCoveredText() + " ");
        }
        return builder.toString();
    }

    // TODO filter against blacklist
    public static SemanticSignature getSemanticSignature(String wikidataId)
    {
        Set<String> relatedRelations = new HashSet<>();
        Set<String> relatedEntities = new HashSet<>();
        String queryString = QueryUtil.semanticSignatureQuery(wikidataId, signatureQueryLimit);
        TupleQuery query = conn.prepareTupleQuery(QueryLanguage.SPARQL, queryString);
        try (TupleQueryResult result = query.evaluate()) {
            while (result.hasNext()) {
                BindingSet sol = result.next();
                relatedEntities.add(sol.getValue("label").stringValue());
                String p = sol.getValue("p").stringValue();
                relatedRelations.add(p.substring(0, p.length() - 2));
            }
        }
        catch (Exception e) {
            logger.error("could not get semantic signature", e);
        }
        
        return new SemanticSignature(relatedEntities, relatedRelations);
    }

    public static int getCandidateQueryLimit()
    {
        return candidateQueryLimit;
    }

    public static int getSignatureQueryLimit()
    {
        return signatureQueryLimit;
    } 
    
}