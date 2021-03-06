package zemberek.morphology.apps;

import com.google.common.base.Splitter;
import com.google.common.base.Stopwatch;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Lists;
import org.antlr.v4.runtime.Token;
import org.junit.Test;
import zemberek.core.collections.Histogram;
import zemberek.core.logging.Log;
import zemberek.core.text.TextUtil;
import zemberek.core.turkish.PrimaryPos;
import zemberek.core.turkish.SecondaryPos;
import zemberek.core.turkish.TurkicSeq;
import zemberek.core.turkish.TurkishAlphabet;
import zemberek.morphology.ambiguity.Z3MarkovModelDisambiguator;
import zemberek.morphology.analysis.SentenceAnalysis;
import zemberek.morphology.analysis.WordAnalysis;
import zemberek.morphology.analysis.tr.TurkishMorphology;
import zemberek.morphology.analysis.tr.TurkishSentenceAnalyzer;
import zemberek.morphology.external.OflazerAnalyzerRunner;
import zemberek.morphology.lexicon.DictionaryItem;
import zemberek.morphology.lexicon.NullSuffixForm;
import zemberek.morphology.lexicon.SuffixForm;
import zemberek.morphology.lexicon.tr.TurkishDictionaryLoader;
import zemberek.morphology.lexicon.tr.TurkishSuffixes;
import zemberek.morphology.structure.Turkish;
import zemberek.tokenizer.ZemberekLexer;
import zemberek.tokenizer.antlr.TurkishLexer;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Collator;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class ZemberekNlpScripts {

    //private static Path DATA_PATH = Paths.get("/media/depo/data/aaa");
    private static Path DATA_PATH = Paths.get("/home/ahmetaa/data/nlp");
    private static Path NLP_TOOLS_PATH = Paths.get("/home/ahmetaa/apps/nlp/tools");
    private static Path OFLAZER_ANALYZER_PATH = NLP_TOOLS_PATH.resolve("Morphological-Analyzer/Turkish-Oflazer-Linux64");


    @Test
    public void generateSuffixNames() throws IOException {
        TurkishSuffixes suffixes = new TurkishSuffixes();
        List<SuffixForm> forms = new ArrayList<>();
        for (SuffixForm form : suffixes.getAllForms()) {
            if (form instanceof NullSuffixForm) {
                continue;
            }
            forms.add(form);
        }
        forms.sort(Comparator.comparing(SuffixForm::getId));
        List<String> result = forms.stream().map(s -> s.id).collect(Collectors.toList());
        Files.write(Paths.get("suffix-list"), result);
    }


    @Test
    public void createZemberekVocabulary() throws IOException {
        Path outDir = DATA_PATH.resolve("out");
        Files.createDirectories(outDir);
        TurkishMorphology parser = TurkishMorphology.createWithDefaults();

        List<String> vocab = new ArrayList<>(parser.getLexicon().size());
        for (DictionaryItem item : parser.getLexicon()) {
            vocab.add(item.lemma);
        }
        vocab.sort(collTr::compare);
        Files.write(outDir.resolve("zemberek.vocab"), vocab);
    }


    @Test
    public void parseLargeVocabularyZemberek() throws IOException {
        Path wordFreqFile = DATA_PATH.resolve("vocab.all.freq");
        Path outDir = DATA_PATH.resolve("out");
        Files.createDirectories(outDir);

        TurkishMorphology parser = TurkishMorphology.createWithDefaults();
        Log.info("Loading histogram.");
        Histogram<String> histogram = Histogram.loadFromUtf8File(wordFreqFile, ' ');
        List<String> accepted = new ArrayList<>(histogram.size() / 3);

        int c = 0;
        for (String s : histogram) {
            List<WordAnalysis> parses = parser.analyze(s);
            if (parses.size() > 0 &&
                    parses.get(0).dictionaryItem.primaryPos != PrimaryPos.Unknown) {
                accepted.add(s);
            }
            if (c > 0 && c % 10000 == 0) {
                Log.info("Processed = " + c);
            }
            c++;
        }

        sortAndSave(outDir.resolve("zemberek-parsed-words.txt"), accepted);
    }

    private static Collator collTr = Collator.getInstance(new Locale("tr"));

    private void sortAndSave(Path outPath, List<String> accepted) throws IOException {
        Log.info("Sorting %d words.", accepted.size());
        accepted.sort(collTr::compare);
        Log.info("Writing.");
        try (PrintWriter pw = new PrintWriter(outPath.toFile(), "utf-8")) {
            accepted.forEach(pw::println);
        }
    }

    @Test
    public void parseLargeVocabularyOflazer() throws IOException {

        OflazerAnalyzerRunner runner = new OflazerAnalyzerRunner(
                OFLAZER_ANALYZER_PATH.toFile(), OFLAZER_ANALYZER_PATH.resolve("tfeaturesulx.fst").toFile());

        //Path wordFile = DATA_PATH.resolve("vocab.all");
        Path wordFile = DATA_PATH.resolve("vocab-corpus-and-zemberek");
        Path outDir = DATA_PATH.resolve("out");
        Files.createDirectories(outDir);

        Path outPath = outDir.resolve("oflazer-parses.txt");
        runner.parseSentences(wordFile.toFile(), outPath.toFile());
    }

    @Test
    public void extractFromOflazerAnalysisResult() throws IOException {
        Path inPath = DATA_PATH.resolve("out").resolve("oflazer-parses.txt");
        List<String> lines = Files.readAllLines(inPath, StandardCharsets.UTF_8);
        Log.info("Loaded.");
        LinkedHashSet<String> accepted = new LinkedHashSet<>(lines.size() / 5);
        for (String line : lines) {
            if (line.trim().length() == 0 || line.endsWith("+?")) {
                continue;
            }
            accepted.add(line.substring(0, line.indexOf('\t')));
        }
        sortAndSave(DATA_PATH.resolve("out").resolve("oflazer-parsed-words.txt"), new ArrayList<>(accepted));
    }

    @Test
    public void reduceOflazerAnalysisResult() throws IOException {
        Path inPath = DATA_PATH.resolve("out").resolve("oflazer-parses.txt");
        List<String> lines = Files.readAllLines(inPath, StandardCharsets.UTF_8);
        Log.info("Loaded.");
        LinkedHashSet<String> accepted = new LinkedHashSet<>(lines.size() / 5);
        for (String line : lines) {
            if (line.trim().length() == 0 || line.endsWith("+?")) {
                continue;
            }
            accepted.add(line.trim());
        }
        sortAndSave(DATA_PATH.resolve("out").resolve("oflazer-analyses.txt"), new ArrayList<>(accepted));
    }

    static TurkishAlphabet alphabet = new TurkishAlphabet();

    @Test
    public void extractTypesFromOflazerAnalysis() throws IOException {
        Path inPath = DATA_PATH.resolve("out").resolve("oflazer-analyses.txt");
        List<String> lines = Files.readAllLines(inPath, StandardCharsets.UTF_8);
        Log.info("Loaded.");
        LinkedHashMultimap<String, String> map = LinkedHashMultimap.create(10, 10000);

        Set<String> secondaryPosKeys = SecondaryPos.converter().getKeysSet();

        for (String line : lines) {
            if (line.trim().length() == 0 || line.endsWith("+?")) {
                continue;
            }
            List<String> parts = Splitter.on("\t").omitEmptyStrings().trimResults().splitToList(line);

            // output sometimes combines root and morph part.
            if (parts.size() < 3) {
                int plusIndex = parts.get(1).indexOf("+");
                String value = parts.get(1).substring(0, plusIndex);
                String morph = parts.get(1).substring(plusIndex + 1);
                parts = Lists.newArrayList(parts.get(0), value, morph);
            }

            String value = parts.get(1);
            List<String> morphs = Splitter.on("+").omitEmptyStrings().trimResults().splitToList(parts.get(2));

            String primaryPos = morphs.get(0).replaceAll("\\^DB", "");
            String secondaryPos = morphs.size() > 1 && secondaryPosKeys.contains(morphs.get(1).replaceAll("\\^DB", ""))
                    ? morphs.get(1).replaceAll("\\^DB", "") : "";

            if (primaryPos.equals("Verb")) {
                TurkicSeq seq = new TurkicSeq(value, alphabet);
                if (seq.lastVowel().isFrontal()) {
                    value = value + "mek";
                } else {
                    value = value + "mak";
                }
            }
            if (primaryPos.equals("Adverb")) {
                primaryPos = "Adv";
            }

            String key = secondaryPos.length() == 0 ? "[P:" + primaryPos + "]" : "[P:" + primaryPos + "," + secondaryPos + "]";
            map.put(key, value);
        }

        Path path = DATA_PATH.resolve("out").resolve("dictionary-from-analysis.txt");
        try (PrintWriter pw = new PrintWriter(path.toFile(), StandardCharsets.UTF_8.name())) {
            for (String key : map.keySet()) {
                List<String> values = new ArrayList<>(map.get(key));
                values.sort(collTr::compare);
                for (String value : values) {
                    pw.println(value + " " + key);
                }
                pw.println();
            }
        }
    }

    @Test
    public void findZemberekMissingOrDifferent() throws IOException {
        Path path = DATA_PATH.resolve("out");
        LinkedHashSet<String> oSet =
                new LinkedHashSet<>(TextUtil.loadLinesWithText(path.resolve("dictionary-from-analysis.txt"))
                        .stream()
                        .filter(s -> !s.contains("Prop")).collect(Collectors.toList()));

        TurkishMorphology parser = TurkishMorphology.createWithDefaults();
        List<String> zemberekTypes = new ArrayList<>(parser.getLexicon().size());
        for (DictionaryItem item : parser.getLexicon()) {
            String lemma = /*item.primaryPos == PrimaryPos.Verb ? item.lemma.replaceAll("mek$|mak$", "") : */item.lemma;
            lemma = Turkish.normalizeCircumflex(lemma);
            String primaryString = /*item.primaryPos == PrimaryPos.Adverb ? "Adverb" :*/ item.primaryPos.shortForm;
            String pos = item.secondaryPos == null
                    || item.secondaryPos == SecondaryPos.Unknown
                    || item.secondaryPos == SecondaryPos.None ?
                    "[P:" + primaryString + "]" : "[P:" + primaryString + "," + item.secondaryPos.shortForm + "]";
            zemberekTypes.add(lemma + " " + pos);
            if(pos.equals("[P:Noun]")) {
                zemberekTypes.add(lemma + " [P:Adj]");
            }
            if(pos.equals("[P:Adj]")) {
                zemberekTypes.add(lemma + " [P:Noun]");
            }

        }
        zemberekTypes.sort(collTr::compare);
        Files.write(path.resolve("found-in-zemberek"), zemberekTypes);
        LinkedHashSet<String> zSet = new LinkedHashSet<>(zemberekTypes);

        oSet.removeAll(zSet);
        Files.write(path.resolve("not-found-in-zemberek"), oSet);
    }


    @Test
    public void generateOnlyOflazer() throws IOException {
        Path inPath = DATA_PATH.resolve("out");
        List<String> zemberekAll =
                Files.readAllLines(inPath.resolve("zemberek-parsed-words.txt"));
        Log.info("Zemberek Loaded.");
        LinkedHashSet<String> onlyOflazer =
                new LinkedHashSet<>(Files.readAllLines(inPath.resolve("oflazer-parsed-words.txt")));
        Log.info("Oflazer Loaded.");
        zemberekAll.forEach(onlyOflazer::remove);
        Log.info("Writing.");
        Files.write(inPath.resolve("only-oflazer.txt"), onlyOflazer);
        Log.info("Oflazer-only saved.");
    }

    @Test
    public void generateOnlyZemberek() throws IOException {
        Path dir = DATA_PATH.resolve("out");
        List<String> oflazerAll =
                Files.readAllLines(dir.resolve("oflazer-parsed-words.txt"));
        Log.info("Oflazer Loaded.");

        LinkedHashSet<String> onlyZemberek =
                new LinkedHashSet<>(Files.readAllLines(dir.resolve("zemberek-parsed-words.txt")));
        Log.info("Zemberek Loaded.");

        oflazerAll.forEach(onlyZemberek::remove);
        Log.info("Writing.");

        Files.write(dir.resolve("only-zemberek.txt"), onlyZemberek);
        Log.info("Zemberek-only saved.");

    }

    @Test
    public void frequentUnknown() throws IOException {

        Path wordFreqFile = DATA_PATH.resolve("vocab.all.freq");

        Log.info("Loading histogram.");
        Histogram<String> histogram = Histogram.loadFromUtf8File(wordFreqFile, ' ');

        Path dir = DATA_PATH.resolve("out");
        List<String> oflazerAll =
                Files.readAllLines(dir.resolve("oflazer-parsed-words.txt"));
        List<String> zemberekAll =
                Files.readAllLines(dir.resolve("zemberek-parsed-words.txt"));

        histogram.removeAll(oflazerAll);
        histogram.removeAll(zemberekAll);

        histogram.removeSmaller(10);

        Files.write(dir.resolve("no-parse-freq.txt"), histogram.getSortedList());
        Files.write(dir.resolve("no-parse-tr.txt"), histogram.getSortedList((a, b) -> collTr.compare(a, b)));
    }

    @Test
    public void unknownZemberek() throws IOException {

        Path wordFreqFile = DATA_PATH.resolve("vocab.all.freq");

        Log.info("Loading histogram.");
        Histogram<String> histogram = Histogram.loadFromUtf8File(wordFreqFile, ' ');

        Path dir = DATA_PATH.resolve("out");

        Log.info("Loading parseable.");
        List<String> zemberekAll =
                Files.readAllLines(dir.resolve("zemberek-parsed-words.txt"));

        histogram.removeAll(zemberekAll);

        //histogram.removeSmaller(10);
        Log.info("Saving.");

        Files.write(dir.resolve("no-parse-zemberek-freq.txt"), histogram.getSortedList());
        Files.write(dir.resolve("no-parse-zemberek-tr.txt"), histogram.getSortedList((a, b) -> collTr.compare(a, b)));
    }

    @Test
    public void generatorTest() throws IOException {
        TurkishMorphology parser = TurkishMorphology.createWithDefaults();
        List<WordAnalysis> result = parser.analyze("besiciliği");
        WordAnalysis first = result.get(0);
        Log.info(first.inflectionalGroups);
    }

    @Test
    public void performance() throws IOException {
        List<String> lines = Files.readAllLines(
                Paths.get("/media/depo/data/aaa/corpora/dunya.100k")
                //Paths.get("/media/depo/data/aaa/corpora/subtitle-1M")
        );

        TurkishMorphology analyzer = TurkishMorphology.builder()
                .addDefaultDictionaries()
                //.disableUnidentifiedTokenAnalyzer()
                //.disableCache()
                .build();

        TurkishSentenceAnalyzer sentenceAnalyzer =
                new TurkishSentenceAnalyzer(analyzer, new Z3MarkovModelDisambiguator());

        Log.info(lines.size() + " lines will be processed.");
        Log.info("Dictionary has " + analyzer.getLexicon().size() + " items.");

        long tokenCount = 0;
        long tokenCountNoPunct = 0;
        Stopwatch clock = Stopwatch.createStarted();
        ZemberekLexer lexer = new ZemberekLexer();
        for (String line : lines) {
            List<Token> tokens = lexer.tokenizeAll(line);
            tokenCount += tokens.stream()
                    .filter(s -> (s.getType() != TurkishLexer.SpaceTab))
                    .count();
            tokenCountNoPunct += tokens.stream()
                    .filter(s -> (s.getType() != TurkishLexer.Punctuation && s.getType() != TurkishLexer.SpaceTab))
                    .count();
        }
        long elapsed = clock.elapsed(TimeUnit.MILLISECONDS);
        Log.info("Elapsed Time = " + elapsed);
        Log.info("Token Count = " + tokenCount);
        Log.info("Token Count (No Punctuation) = " + tokenCountNoPunct);
        Log.info("Tokenization Speed = %.1f tokens/sec",
                tokenCount * 1000d / elapsed);
        Log.info("Tokenization Speed (No Punctuation) = %.1f tokens/sec ",
                tokenCountNoPunct * 1000d / elapsed);
        Log.info("");
        Log.info("Sentence word analysis test:");
        int counter = 0;
        clock.reset().start();
        for (String line : lines) {
            try {
                SentenceAnalysis res = sentenceAnalyzer.analyze(line);
                counter += res.size(); // for preventing VM optimizations.
            } catch (Exception e) {
                Log.info(line);
                e.printStackTrace();
            }
        }
        elapsed = clock.elapsed(TimeUnit.MILLISECONDS);
        Log.info("Elapsed Time = " + elapsed);
        Log.info("Tokenization + Analysis speed = %.1f tokens/sec"
                , tokenCount * 1000d / elapsed);
        Log.info("Tokenization + Analysis speed (no punctuation) = %.1f tokens/sec"
                , tokenCountNoPunct * 1000d / elapsed);
        Log.info("");

        Log.info("Disambiguation Test:");
        analyzer.invalidateAllCache();
        clock.reset().start();
        for (String line : lines) {
            try {
                List<WordAnalysis> results = sentenceAnalyzer.bestParse(line);
                counter += results.size(); // for preventing VM optimizations.
            } catch (Exception e) {
                Log.info(line);
                e.printStackTrace();
            }
        }
        elapsed = clock.elapsed(TimeUnit.MILLISECONDS);
        Log.info("Elapsed Time = " + elapsed);
        Log.info("Tokenization + Analysis + Disambiguation speed = %.1f tokens/sec"
                , tokenCount * 1000d / elapsed);
        Log.info("Tokenization + Analysis + Disambiguation speed (no punctuation) = %.1f tokens/sec"
                , tokenCountNoPunct * 1000d / elapsed);
        Log.info(counter);
    }

    @Test
    public void testWordAnalysis() throws IOException {
        TurkishMorphology morphology = TurkishMorphology.createWithDefaults();
        List<WordAnalysis> results = morphology.analyze("phpye");
        for (WordAnalysis result : results) {
            Log.info(result.formatLong());
            Log.info("\tStems = " + result.getStems());
            Log.info("\tLemmas = " + result.getLemmas());
        }
    }

    @Test
    public void testSentenceAnalysis() throws IOException {
        TurkishMorphology morphology = TurkishMorphology.createWithDefaults();
        Z3MarkovModelDisambiguator disambiguator = new Z3MarkovModelDisambiguator();
        TurkishSentenceAnalyzer analyzer = new TurkishSentenceAnalyzer(morphology, disambiguator);

        String sentence = "Kırmızı kalemi al.";
        Log.info("Sentence  = " + sentence);
        SentenceAnalysis analysis = analyzer.analyze(sentence);

        Log.info("Before disambiguation.");
        writeParseResult(analysis);

        Log.info("\nAfter disambiguation.");
        analyzer.disambiguate(analysis);
        writeParseResult(analysis);
    }

    private void writeParseResult(SentenceAnalysis analysis) {
        for (SentenceAnalysis.Entry entry : analysis) {
            Log.info("Word = " + entry.input);
            for (WordAnalysis w : entry.parses) {
                Log.info(w.formatLong());
            }
        }
    }

    @Test
    public void memoryStressTest() throws IOException {
        List<String> words = Files.readAllLines(Paths.get("dunya"));
        TurkishMorphology parser = TurkishMorphology.createWithDefaults();

        int c = 0;
        for (int i = 0; i < 100; i++) {
            Stopwatch sw = Stopwatch.createStarted();
            for (String s : words) {
                List<WordAnalysis> parses = parser.analyze(s);
                c += parses.size();
            }
            Log.info(sw.elapsed(TimeUnit.MILLISECONDS));
        }

        Log.info(c);
    }


    @Test
    public void generateWords() throws IOException {
        getStrings();
    }


    @Test
    public void disambiguationMemoryTest() throws IOException {
        List<String> lines = Files.readAllLines(Paths.get("/media/depo/data/aaa/corpora/dunya.100k"));
        TurkishMorphology parser = TurkishMorphology.createWithDefaults();
        TurkishSentenceAnalyzer sentenceAnalyzer = new TurkishSentenceAnalyzer(parser,
                new Z3MarkovModelDisambiguator());

        int k = 0;
        for (int i = 0; i < 100; i++) {
            Stopwatch sw = Stopwatch.createStarted();
            for (String line : lines) {
                k += sentenceAnalyzer.bestParse(line).size();
            }
            Log.info(sw.elapsed(TimeUnit.MILLISECONDS));
        }
        Log.info(k);
    }


    private LinkedHashSet<String> getStrings() throws IOException {
        List<String> lines = Files.readAllLines(Paths.get("/media/depo/data/aaa/corpora/dunya.500k"));
        LinkedHashSet<String> words = new LinkedHashSet<>();
        ZemberekLexer lexer = new ZemberekLexer();
        for (String line : lines) {
            words.addAll(lexer.tokenStrings(line));
        }
        Log.info("Line count = %d", lines.size());
        Log.info("Unique word count = %d", words.size());
        Files.write(Paths.get("dunya"), words);
        return words;
    }

    @Test
    public void parseLargeVocabularyZemberekForMorfessor() throws IOException {
        Path wordFreqFile = DATA_PATH.resolve("vocab.all.freq");
        Path outDir = DATA_PATH.resolve("out");
        Files.createDirectories(outDir);

        TurkishMorphology parser = TurkishMorphology.createWithDefaults();
        Log.info("Loading histogram.");
        Histogram<String> histogram = Histogram.loadFromUtf8File(wordFreqFile, ' ');
        histogram.removeSmaller(1000);
        List<String> accepted = new ArrayList<>(histogram.size());

        int c = 0;
        for (String s : histogram) {
            s = s.trim();
            if (s.length() < 4) {
                continue;
            }
            List<WordAnalysis> parses = parser.analyze(s);
            if (parses.size() > 0 &&
                    parses.get(0).dictionaryItem.primaryPos != PrimaryPos.Unknown) {
                LinkedHashSet<String> k = new LinkedHashSet<>(2);
                for (WordAnalysis parse : parses) {
                    if (parse.dictionaryItem.lemma.length() > 1) {
                        String str = parse.root + " " + String.join(" ", parse.suffixSurfaceList()).replaceAll("[ ]+", " ").trim();
                        k.add(str);
                    }
                }

                String join = String.join(", ", k).trim();
                if (!s.equals(join) && join.length() > 2) {
                    accepted.add(s + " " + join);
                }
            }
            if (c > 0 && c % 10000 == 0) {
                Log.info("Processed = " + c);
            }
            c++;
        }
        sortAndSave(outDir.resolve("morfessor-annotation.txt"), accepted);
    }

    @Test
    public void generateMorfessorData() throws IOException {
        Path wordFreqFile = DATA_PATH.resolve("vocab.all.freq");
        Path outDir = DATA_PATH.resolve("out");
        Log.info("Loading histogram.");
        Histogram<String> histogram = Histogram.loadFromUtf8File(wordFreqFile, ' ');
        histogram.removeSmaller(50);

        try (
                PrintWriter pw = new PrintWriter("/media/depo/data/aaa/morfessor.list");
                PrintWriter pw2 = new PrintWriter("/media/depo/data/aaa/morfessor.list.freq")
        ) {
            for (String s : histogram) {
                pw.println(s);
/*                int reduced= (int) LogMath.log(1.005, histogram.getCount(s)-49);
                if(reduced==0) {
                    reduced+=1;
                }*/
                pw2.println(histogram.getCount(s) + " " + s);
            }
        }
    }

}
