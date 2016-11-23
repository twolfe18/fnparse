package edu.jhu.hlt.ikbp.tac;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.EntityMention;
import edu.jhu.hlt.concrete.EntityMentionSet;
import edu.jhu.hlt.concrete.Tokenization;
import edu.jhu.hlt.concrete.serialization.iterators.TarGzArchiveEntryCommunicationIterator;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.TimeMarker;

/**
 * @deprecated
 * @author travis
 */
public class ScanCAGForMentions {
  
  static class Matcher {
    int index;  // for internal usage
    String nerType;
    String[] tokens;
    String head;
    List<String> ngrams;

    public Matcher(String head, String[] tokens, String nerType) {
      this.tokens = tokens;
      this.nerType = nerType;
      this.head = head;
    }
  }

  private List<Matcher> matchers;
  private Map<String, List<Matcher>> nGramIndex;    // keys are lowercased substrings which much appear in a mention
  private int ngram = 5;
  private double minJaccardWrong = 0.4; // 2*|intersect|/(|a|+|b|)
  
  public ScanCAGForMentions() {
    matchers = new ArrayList<>();
    nGramIndex = new HashMap<>();
  }
  
  public void addSimple(String headword, String whiteSpaceDelim, String nerType) {
    String[] tokens = whiteSpaceDelim.split("\\s+");
    Matcher m = new Matcher(headword, tokens, nerType);
    m.index = matchers.size();
    m.ngrams = ngrams(whiteSpaceDelim, ngram);
    matchers.add(m);
    
    for (String key : m.ngrams) {
      System.out.println(key);
      List<Matcher> ms = nGramIndex.get(key);
      if (ms == null) {
        ms = new ArrayList<>();
        nGramIndex.put(key, ms);
      }
      ms.add(m);
    }
  }
  
  public static List<String> ngrams(String t, int ngrams) {
    List<String> ng = new ArrayList<>();
    String lc = t.toLowerCase();
    String padded = "";
    for (int i = 0; i < ngrams-1; i++)
      padded += "B";
    padded += lc;
    for (int i = 0; i < ngrams-1; i++)
      padded += "A";
    char[] cs = padded.toCharArray();
    for (int offset = 1; offset < padded.length()-ngrams; offset++)
      ng.add(new String(cs, offset, ngrams));
    return ng;
  }
  
  public boolean matchSimple(String t, String head, String nerType) {
    Counts<Integer> matcherCounts = new Counts<>();
    List<String> ngrams = ngrams(t, ngram);
    for (String key : ngrams) {
      List<Matcher> ms = nGramIndex.get(key);
      if (ms != null) {
        for (Matcher m : ms) {
          if (m.nerType == null || m.nerType.equals(nerType)) {
            if (m.head == null || m.head.equalsIgnoreCase(head)) {
//              return true;
              matcherCounts.increment(m.index);
            }
          }
        }
      }
    }
//    return !matcherCounts.countIsAtLeast(ngramMatchMin).isEmpty();
    for (int mi : matcherCounts.countIsAtLeast(2)) {
      Matcher m = matchers.get(mi);
      double j = 2 * matcherCounts.getCount(mi);
      j /= m.ngrams.size() + ngrams.size();
      if (j >= minJaccardWrong)
        return true;
    }
    return false;
  }
  
  public void scan(Communication c) {
//    Log.info("scanning " + c.getId());
    if (!c.isSetEntityMentionSetList())
      return;
    
    Map<String, Tokenization> tokz = AddNerTypeToEntityMentions.buildTokzIndex(c);
    
    EntityMentionSet ems = c.getEntityMentionSetList().get(0);
    int found = 0;
    for (EntityMention em : ems.getMentionList()) {
      String t = em.getText();
      String nerType = em.getEntityType();
//      System.out.println("nerType: " + nerType);
      String head = null;
      if (em.getTokens().isSetAnchorTokenIndex()) {
        Tokenization tk = tokz.get(em.getTokens().getTokenizationId().getUuidString());
        head = tk.getTokenList().getTokenList().get(em.getTokens().getAnchorTokenIndex()).getText();
      }
      if (matchSimple(t, head, nerType)) {
        System.out.println("found: " + nerType + "\t" + head + "\t" + t);
        found++;
      }
    }
//    System.out.println("found=" + found);
  }
  
  public static void main(String[] args) throws Exception {
    
    if (true) {
      for (String t : ngrams("foo", 5)) {
        System.out.println(t);
      }
      return;
    }
    
    
    ScanCAGForMentions scanner = new ScanCAGForMentions();

//    scanner.addSimple("Obama", "Barack Obama", "PERSON");
//    grep '<name>' tac_2014_kbp_english_EDL_evaluation_queries.xml | perl -pe 's/\s+<name>//' | perl -pe 's/<\/name>//' | shuf -n 30 | perl -pe 's/(.*)/scanner.addSimple("\1", "\1", "PERSON");/'
    scanner.addSimple("KOTB", "KOTB", "ORGANIZATION");
    scanner.addSimple("Gibson", "James Gibson", "PERSON");
    scanner.addSimple("Drudgewire", "Drudgewire", "ORGANIZATION");
    scanner.addSimple("Va.", "Va.", "LOCATION");
    scanner.addSimple("Microsoft", "Microsoft Ireland", "ORGANIZATION");
    scanner.addSimple("Dostoevsky", "Dostoevsky", "PERSON");
    scanner.addSimple("Fatality", "Fatality", "PERSON");
    scanner.addSimple("Sharpton", "Al Sharpton", "PERSON");
    scanner.addSimple("McIntyre", "McIntyre", "PERSON");
    scanner.addSimple("Freedom", "New Freedom", "ORGANIZATION");
    scanner.addSimple("Vujicic", "Nick Vujicic", "PERSON");
    scanner.addSimple("Tyson", "Tyson", "PERSON");
    scanner.addSimple("Prince", "Prince", "PERSON");
    scanner.addSimple("Sen", "Amartya Sen", "PERSON");
    scanner.addSimple("Kevin", "Kevin", "PERSON");
    scanner.addSimple("News", "ABC News", "ORGANIZATION");
    scanner.addSimple("Blazers", "Portland Trail Blazers", "ORGANIZATION");
    scanner.addSimple("Bush", "Bush", "PERSON");
    scanner.addSimple("T", "The T", "PERSON");
    scanner.addSimple("Burlington", "Burlington", "LOCATION");
    scanner.addSimple("JFK", "JFK", "LOCATION");
    scanner.addSimple("Democratic", "Democratic", "ORGANIZATION");
    scanner.addSimple("Post", "Washington Post", "ORGANIZATION");
    scanner.addSimple("Gallagher", "Carol Gallagher", "PERSON");
    scanner.addSimple("Erving", "Erving", "PERSON");
    scanner.addSimple("Scientist", "Mad Scientist", "PERSON");
    scanner.addSimple("Utah", "Utah", "LOCATION");
    scanner.addSimple("Council", "California State Council of Service Employees", "ORGANIZATION");
    scanner.addSimple("FreeDuck", "FreeDuck", "ORGANIZATION");
    scanner.addSimple("Trajan", "Trajan", "PERSON");

    File f = new File("data/concretely-annotated-gigaword/sample-frank-oct16/nyt_eng_200909.tar.gz");
    TimeMarker tm = new TimeMarker();
    int max = 100000000, n = 0;
    try (InputStream is = new FileInputStream(f);
        TarGzArchiveEntryCommunicationIterator iter = new TarGzArchiveEntryCommunicationIterator(is)) {
      while (iter.hasNext()) {
        Communication c = iter.next();
        new AddNerTypeToEntityMentions(c);
        scanner.scan(c);
        
        if (n++ == max)
          break;
        
        if (tm.enoughTimePassed(10))
          Log.info("scanned " + n + " docs");
      }
    }
    Log.info("done");
  }
  
  /* grep nerType /tmp/ScanCAGForMentions.txt | sort | uniq -c | sort -rn | head -n 40
 697587 nerType: O
 123312 nerType: PERSON
  64830 nerType: LOCATION
  63556 nerType: ORGANIZATION
  54396 nerType: NUMBER
  43906 nerType: DATE
  17477 nerType: MISC
  14619 nerType: DURATION
  13547 nerType: NUMBER,O
  10539 nerType: O,MISC,O
  10087 nerType: O,NUMBER,O
   9733 nerType: O,ORGANIZATION,O
   9264 nerType: O,LOCATION
   9196 nerType: ORDINAL
   8236 nerType: O,LOCATION,O
   8049 nerType: O,PERSON,O
   7583 nerType: O,ORGANIZATION
   7201 nerType: PERSON,O
   6440 nerType: O,DATE
   6177 nerType: O,PERSON
   5722 nerType: O,DATE,O
   4492 nerType: LOCATION,O
   4244 nerType: ORGANIZATION,O
   4191 nerType: O,ORDINAL,O
   3425 nerType: TIME
   3103 nerType: MISC,O
   2696 nerType: O,NUMBER
   1939 nerType: DATE,O
   1687 nerType: O,MISC
   1655 nerType: O,LOCATION,O,LOCATION
   1385 nerType: O,MONEY,O
   1252 nerType: O,DURATION
   1111 nerType: NUMBER,O,NUMBER,O
    875 nerType: O,PERSON,O,PERSON
    814 nerType: O,NUMBER,DURATION
    803 nerType: O,ORGANIZATION,O,LOCATION
    756 nerType: O,PERSON,O,PERSON,O
    712 nerType: O,NUMBER,O,NUMBER,O
    672 nerType: O,SET,O
    653 nerType: O,MONEY
   */
}
