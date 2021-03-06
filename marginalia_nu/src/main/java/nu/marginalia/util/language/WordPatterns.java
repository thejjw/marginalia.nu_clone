package nu.marginalia.util.language;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;

public class WordPatterns {
    public static final int MIN_WORD_LENGTH = 1;
    public static final int MAX_WORD_LENGTH = 64;

    public static final String WORD_TOKEN_JOINER = "_";
    public static final Pattern wordPattern = Pattern.compile("[#]?[_@.a-zA-Z0-9'+\\-\\u00C0-\\u00D6\\u00D8-\\u00f6\\u00f8-\\u00ff]+[#]?");
    public static final Pattern wordPatternRestrictive = Pattern.compile("[#]?[@a-zA-Z0-9'+\\-\\u00C0-\\u00D6\\u00D8-\\u00f6\\u00f8-\\u00ff]+[#]?");
    public static final Pattern keyWordPattern = Pattern.compile("[A-Z\\u00C0-\\u00D6][_a-zA-Z\\u00C0-\\u00D6\\u00D8-\\u00f6\\u00f8-\\u00ff]{0,32}('[a-zA-Z])?");
    public static final Pattern wordAppendixPattern = Pattern.compile("[.]?[0-9a-zA-Z\\u00C0-\\u00D6\\u00D8-\\u00f6\\u00f8-\\u00ff]{1,3}[0-9]?");
    public static final Pattern joinWord = Pattern.compile("(as|an|the|of|in|a)");
    public static final Pattern keywordAppendixPattern = Pattern.compile("([0-9A-Z][A-Z0-9]{0,3})");
    public static final Pattern wordBreakPattern = Pattern.compile("([^_#@.a-zA-Z'+\\-0-9\\u00C0-\\u00D6\\u00D8-\\u00f6\\u00f8-\\u00ff]+)|[|]|(\\.(\\s+|$))");
    public static final Pattern characterNoisePattern = Pattern.compile("^[/+\\-]+$");

    public static final Predicate<String> wordQualitiesPredicate = wordPattern.asMatchPredicate();
    public static final Predicate<String> restrictivePredicate = wordPatternRestrictive.asMatchPredicate();
    public static final Predicate<String> wordAppendixPredicate = wordAppendixPattern.asMatchPredicate();
    public static final Predicate<String> keywordPredicate = keyWordPattern.asMatchPredicate();
    public static final Predicate<String> keywordAppendixPredicate = keywordAppendixPattern.asMatchPredicate();
    public static final Predicate<String> wordPredicateEither = wordQualitiesPredicate.or(wordAppendixPredicate);
    public static final Predicate<String> keywordPredicateEither = keywordPredicate.or(keywordAppendixPredicate);
    public static final Predicate<String> characterNoisePredicate = characterNoisePattern.asMatchPredicate();

    public static final Set<String> topWords;
    static {
        topWords = new HashSet<>(200);
        try (var resource = Objects.requireNonNull(ClassLoader.getSystemResourceAsStream("dictionary/en-stopwords"),
                "Could not load word frequency table");
             var br = new BufferedReader(new InputStreamReader(resource))
        ) {
            while (true) {
                String s = br.readLine();
                if (s == null) {
                    break;
                }
                topWords.add(s);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static boolean hasMoreThanTwo(String s, char c, int max) {
        int idx = 0;
        for (int i = 0; i <= max; i++) {
            idx = s.indexOf(c, idx+1);
            if (idx < 0 || idx >= s.length() - 1)
                return false;
        }
        return true;
    }


    public static boolean filter(String word) {
        if (word.isBlank()) {
            return false;
        }
        if (hasMoreThanTwo(word, '-', 2)) {
            return false;
        }
        if (hasMoreThanTwo(word, '+', 2)) {
            return false;
        }
        if (word.startsWith("-")
                || word.endsWith("-")
        ) {
            return false;
        }

        int numDigits = 0;
        for (int i = 0; i < word.length(); i++) {
            if (Character.isDigit(word.charAt(i))) {
                numDigits++;
            }
            if (numDigits > 6)
                return false;
        }

        return true;
    }

    public static boolean filterStrict(String word) {

        int numDigits = (int) word.chars().filter(Character::isDigit).count();
        if (numDigits == word.length()) {
            return false;
        }

        return true;
    }

    public static boolean isStopWord(String s) {
        if (s.length() < MIN_WORD_LENGTH) {
            return true;
        }
        if (!wordQualitiesPredicate.test(s)) {
            return true;
        }
        if (!filter(s)) {
            return true;
        }
        if (topWords.contains(s.toLowerCase())) {
            return true;
        }
        return false;
    }

}
