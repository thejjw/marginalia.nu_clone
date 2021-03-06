package nu.marginalia.wmsa.edge.converting.processor;

import com.google.common.hash.HashCode;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import nu.marginalia.wmsa.edge.converting.model.DisqualifiedException;
import nu.marginalia.wmsa.edge.converting.model.DisqualifiedException.DisqualificationReason;
import nu.marginalia.wmsa.edge.converting.model.ProcessedDocument;
import nu.marginalia.wmsa.edge.converting.model.ProcessedDocumentDetails;
import nu.marginalia.wmsa.edge.converting.processor.logic.*;
import nu.marginalia.wmsa.edge.converting.processor.logic.FeedExtractor;
import nu.marginalia.wmsa.edge.converting.processor.logic.LinkParser;
import nu.marginalia.util.language.LanguageFilter;
import nu.marginalia.util.language.processing.DocumentKeywordExtractor;
import nu.marginalia.util.language.processing.SentenceExtractor;
import nu.marginalia.util.language.processing.model.DocumentLanguageData;
import nu.marginalia.wmsa.edge.converting.processor.logic.HtmlFeature;
import nu.marginalia.wmsa.edge.converting.processor.logic.HtmlStandardExtractor;
import nu.marginalia.wmsa.edge.crawling.model.CrawledDocument;
import nu.marginalia.wmsa.edge.crawling.model.CrawledDomain;
import nu.marginalia.wmsa.edge.crawling.model.CrawlerDocumentStatus;
import nu.marginalia.wmsa.edge.index.model.IndexBlock;
import nu.marginalia.wmsa.edge.model.EdgeUrl;
import nu.marginalia.wmsa.edge.model.crawl.EdgeHtmlStandard;
import nu.marginalia.wmsa.edge.model.crawl.EdgePageWordSet;
import nu.marginalia.wmsa.edge.model.crawl.EdgeUrlState;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URISyntaxException;
import java.util.*;

import static nu.marginalia.wmsa.edge.model.crawl.EdgeHtmlStandard.UNKNOWN;

public class DocumentProcessor {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final int minDocumentLength;
    private final double minDocumentQuality;

    private static final Set<String> acceptedContentTypes = Set.of("application/xhtml+xml", "application/xhtml", "text/html");

    private final SentenceExtractor sentenceExtractor;
    private final FeatureExtractor featureExtractor;
    private final TitleExtractor titleExtractor;
    private final DocumentKeywordExtractor keywordExtractor;
    private final SummaryExtractor summaryExtractor;

    private static final DocumentValuator documentValuator = new DocumentValuator();
    private static final LanguageFilter languageFilter = new LanguageFilter();
    private static final LinkParser linkParser = new LinkParser();
    private static final FeedExtractor feedExtractor = new FeedExtractor(linkParser);

    @Inject
    public DocumentProcessor(@Named("min-document-length") Integer minDocumentLength,
                             @Named("min-document-quality") Double minDocumentQuality,
                             SentenceExtractor sentenceExtractor,
                             FeatureExtractor featureExtractor,
                             TitleExtractor titleExtractor,
                             DocumentKeywordExtractor keywordExtractor,
                             SummaryExtractor summaryExtractor)
    {
        this.minDocumentLength = minDocumentLength;
        this.minDocumentQuality = minDocumentQuality;
        this.sentenceExtractor = sentenceExtractor;
        this.featureExtractor = featureExtractor;
        this.titleExtractor = titleExtractor;
        this.keywordExtractor = keywordExtractor;
        this.summaryExtractor = summaryExtractor;
    }

    public ProcessedDocument process(CrawledDocument crawledDocument, CrawledDomain crawledDomain) {
        ProcessedDocument ret = new ProcessedDocument();

        try {
            ret.url = new EdgeUrl(crawledDocument.url);
            ret.state = crawlerStatusToUrlState(crawledDocument.crawlerStatus, crawledDocument.httpStatus);

            if (ret.state == EdgeUrlState.OK) {

                if (isAcceptedContentType(crawledDocument)) {
                    var detailsWords = createDetails(crawledDomain, crawledDocument);

                    if (detailsWords.details().quality < minDocumentQuality) {
                        throw new DisqualifiedException(DisqualificationReason.QUALITY);
                    }

                    ret.details = detailsWords.details();
                    ret.words = detailsWords.words();
                }
                else {
                    throw new DisqualifiedException(DisqualificationReason.CONTENT_TYPE);
                }
            }
            else {
                throw new DisqualifiedException(DisqualificationReason.STATUS);
            }
        }
        catch (DisqualifiedException ex) {
            ret.state = EdgeUrlState.DISQUALIFIED;
            logger.info("Disqualified {}: {}", ret.url, ex.reason);
        }
        catch (Exception ex) {
            ret.state = EdgeUrlState.DISQUALIFIED;
            logger.info("Failed to convert " + ret.url, ex);
            ex.printStackTrace();
        }

        return ret;
    }

    private boolean isAcceptedContentType(CrawledDocument crawledDocument) {
        return crawledDocument.contentType != null && acceptedContentTypes.contains(crawledDocument.contentType.toLowerCase());
    }

    private EdgeUrlState crawlerStatusToUrlState(String crawlerStatus, int httpStatus) {
        return switch (CrawlerDocumentStatus.valueOf(crawlerStatus)) {
            case OK -> httpStatus < 300 ? EdgeUrlState.OK : EdgeUrlState.DEAD;
            case REDIRECT -> EdgeUrlState.REDIRECT;
            default -> EdgeUrlState.DEAD;
        };
    }

    private DetailsWithWords createDetails(CrawledDomain crawledDomain, CrawledDocument crawledDocument)
            throws DisqualifiedException, URISyntaxException {

        var doc = Jsoup.parse(crawledDocument.documentBody);
        var dld = sentenceExtractor.extractSentences(doc.clone());

        checkDocumentLanguage(dld);

        var ret = new ProcessedDocumentDetails();

        ret.description = getDescription(doc);
        ret.length = getLength(doc);
        ret.standard = getHtmlStandard(doc);
        ret.title = titleExtractor.getTitleAbbreviated(doc, dld, crawledDocument.url);
        ret.features = featureExtractor.getFeatures(crawledDomain, doc);
        ret.quality = documentValuator.getQuality(ret.standard, doc, dld);
        ret.hashCode = HashCode.fromString(crawledDocument.documentBodyHash).asLong();

        var words = getWords(dld);

        var url = new EdgeUrl(crawledDocument.url);
        addMetaWords(ret, url, crawledDomain, words);

        getLinks(url, ret, doc, words);

        return new DetailsWithWords(ret, words);
    }

    private void addMetaWords(ProcessedDocumentDetails ret, EdgeUrl url, CrawledDomain domain, EdgePageWordSet words) {
        List<String> tagWords = new ArrayList<>();

        var edgeDomain = url.domain;
        tagWords.add("format:"+ret.standard.toString().toLowerCase());


        tagWords.add("site:" + edgeDomain.toString().toLowerCase());
        if (!Objects.equals(edgeDomain.toString(), edgeDomain.domain)) {
            tagWords.add("site:" + edgeDomain.domain.toLowerCase());
        }

        tagWords.add("proto:"+url.proto.toLowerCase());
        tagWords.add("js:" + Boolean.toString(ret.features.contains(HtmlFeature.JS)).toLowerCase());

        if (ret.features.contains(HtmlFeature.MEDIA)) {
            tagWords.add("special:media");
        }
        if (ret.features.contains(HtmlFeature.TRACKING)) {
            tagWords.add("special:tracking");
        }
        if (ret.features.contains(HtmlFeature.AFFILIATE_LINK)) {
            tagWords.add("special:affiliate");
        }
        if (ret.features.contains(HtmlFeature.COOKIES)) {
            tagWords.add("special:cookies");
        }

        words.append(IndexBlock.Meta, tagWords);
        words.append(IndexBlock.Words, tagWords);
    }

    private void getLinks(EdgeUrl baseUrl, ProcessedDocumentDetails ret, Document doc, EdgePageWordSet words) {
        var links = doc.getElementsByTag("a");
        var frames = doc.getElementsByTag("frame");
        var feeds = doc.select("link[rel=alternate]");

        LinkProcessor lp = new LinkProcessor(ret, baseUrl);

        for (var atag : links) {
            linkParser.parseLink(baseUrl, atag).ifPresent(lp::accept);
        }
        for (var frame : frames) {
            linkParser.parseFrame(baseUrl, frame).ifPresent(lp::accept);
        }

        for (var link : feeds) {
            feedExtractor
                    .getFeedFromAlternateTag(baseUrl,  link)
                    .ifPresent(lp::acceptFeed);
        }

        Set<String> linkTerms = new HashSet<>();

        for (var domain : lp.getForeignDomains()) {
            linkTerms.add("links:"+domain.toString().toLowerCase());
            linkTerms.add("links:"+domain.getDomain().toLowerCase());
        }

        words.append(IndexBlock.Meta, linkTerms);

    }

    private void checkDocumentLanguage(DocumentLanguageData dld) throws DisqualifiedException {
        if (dld.totalNumWords() < minDocumentLength) {
            throw new DisqualifiedException(DisqualificationReason.LENGTH);
        }

        double languageAgreement = languageFilter.dictionaryAgreement(dld);
        if (languageAgreement < 0.1) {
            throw new DisqualifiedException(DisqualificationReason.LANGUAGE);
        }
    }

    private EdgeHtmlStandard getHtmlStandard(Document doc) {
        EdgeHtmlStandard htmlStandard = HtmlStandardExtractor.parseDocType(doc.documentType());

        if (UNKNOWN.equals(htmlStandard)) {
            return HtmlStandardExtractor.sniffHtmlStandard(doc);
        }
        return htmlStandard;
    }

    private EdgePageWordSet getWords(DocumentLanguageData dld) {
        return keywordExtractor.extractKeywords(dld);
    }

    private String getDescription(Document doc) {
        return summaryExtractor.extractSummary(doc).orElse("");
    }

    private int getLength(Document doc) {
        return doc.text().length();
    }

    private record DetailsWithWords(ProcessedDocumentDetails details, EdgePageWordSet words) {}
}
