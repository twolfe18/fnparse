package edu.jhu.hlt.uberts.srl;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.Constituent;
import edu.jhu.hlt.concrete.Dependency;
import edu.jhu.hlt.concrete.DependencyParse;
import edu.jhu.hlt.concrete.MentionArgument;
import edu.jhu.hlt.concrete.Parse;
import edu.jhu.hlt.concrete.Section;
import edu.jhu.hlt.concrete.Sentence;
import edu.jhu.hlt.concrete.SituationMention;
import edu.jhu.hlt.concrete.SituationMentionSet;
import edu.jhu.hlt.concrete.TaggedToken;
import edu.jhu.hlt.concrete.TextSpan;
import edu.jhu.hlt.concrete.Token;
import edu.jhu.hlt.concrete.TokenRefSequence;
import edu.jhu.hlt.concrete.TokenTagging;
import edu.jhu.hlt.concrete.Tokenization;
import edu.jhu.hlt.concrete.serialization.iterators.TarGzArchiveEntryCommunicationIterator;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.Timer;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.io.FactWriter;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator;

/**
 * Converts {@link Communication}s into facts ({@link HypEdge}s) for use by
 * the uberts tools. Outputs in {@link ManyDocRelationFileIterator} format
 * where each startdoc's ARG0 is "<communicationUuid>/<sentenceUuid>".
 *
 * Currently expects the concrete-stanford annotations to be present and
 * adds word2, lemma2, dsyn3-basic, dsyn3-col, and dsyn3-colcc relations/facts.
 *
 * NOTE: Previous work on SRL via uberts used {@link FNParseToRelations}, not this.
 *
 * @author travis
 */
public class ConcreteToRelations extends FactWriter {
  
  public boolean writeTokloc = false;
  public boolean writeSegment4 = false;    // character offsets?

  public ConcreteToRelations(File f) {
    super(f);
  }

  // TODO update this
  public void writeDefs(BufferedWriter w) throws IOException {
    w.write("def word2 <tokenIndex> <word>");
    w.newLine();
    w.write("def tag3 <taggingType> <tokenIndex> <tag> # derived from TokenTaggings");
    w.newLine();
    w.write("def dsyn3 <govTokIdx> <depTokIdx> <edgeLabel> # derived from DependencyParse");
    w.newLine();
//    w.write("def csyn3 <startTokIdxIncl> <endTokIdxExcl> <constituentLabel> # derived from Parse");
//    w.newLine();
    w.write("def csyn5-stanford <csyn5id> <csyn5id> <tokenIndex> <span> <phrase> # id, parent id, head token, span (inclusive-exclusive), phrase label");
    w.newLine();

    // <location> or <predLoc> can be:
    // "4" for a tokenIndex
    // "3+2" for start+width
    // "3]4" for start]last
    // "3)5" for start)end
    // "3)5@4" for start)end@head
    // => This is nice because it doesn't overlap with other common AMBIGUOUS formats like "3-5" and "3,5"
    // I could make this a separate table, but then the 0-lookup value of this data goes down
    w.write("def event2 <predLoc> <predType> # derived from SituationMention.tokens");
    w.newLine();
    w.write("def srl4 <predLoc> <predType> <argLoc> <roleType> # derived from SituationMention.MentionArgument.tokens");
    w.newLine();

    w.write("def entity2 <entityLoc> <entityType>");
    w.newLine();

    // For "sentence", the Tokenization uuid is written out
    w.write("def segment4 <uuid> <sentence|section> <startTokIdxIncl> <endTokIdxExcl> # derived from Sections and Sentence/Tokenizations");
    w.newLine();
    if (writeTokloc) {
      w.write("def tokloc3 <tokenIndex> <startCharIdxIncl> <endCharIdxExcl>");
      w.newLine();
    }

    /*
     * Example transform, discrepancy between concrete-generation and uberts-consumption:
     * "x tag3 pos 0 NNP"
     * In concrete I just want to define TokenTagging => tag3 Relation
     * In uberts I want a short path 0:tokenIndex --pos2--> NNP:posTag, not 0:tokenIndex --tag3--> pos:tagType --backwards[tag3]--> NNP:tag
     */
  }
  
  /**
   * @return true if we found the tool and wrote out the facts.
   */
  private boolean writeTags(String tokenTagginToolname, String tokenTaggingType, Sentence s, String relationName, boolean logFail) throws IOException {
    // Find the proper tags by tool name
    TokenTagging keep = null;
    for (TokenTagging tt : s.getTokenization().getTokenTaggingList()) {
      if (tokenTagginToolname.equals(tt.getMetadata().getTool())
          && tokenTaggingType.equals(tt.getTaggingType())) {
        assert keep == null;
        keep = tt;
      }
    }
    if (keep == null) {
      if (logFail) {
        Log.info("didn't find TokenTagging with tool name: " + tokenTagginToolname + " in Sentence " + s.getUuid());
        for (TokenTagging tt : s.getTokenization().getTokenTaggingList())
          System.out.println(tt.getMetadata().getTool());
      }
      return false;
    }

    // Output the facts
    assert s.getTokenization().getTokenList().getTokenListSize()
        == keep.getTaggedTokenListSize();
    for (TaggedToken tt : keep.getTaggedTokenList()) {
      write("x", relationName, tt.getTokenIndex(), tt.getTag());
    }
    return true;
  }
  
  /**
   * @return true if we found the tool and wrote out the facts.
   */
  public boolean writeDeps(String depsToolname, Sentence s, String relationName, boolean logFail) throws IOException {
    // Find the relevant parse
    DependencyParse keep = null;
    for (DependencyParse dp : s.getTokenization().getDependencyParseList()) {
      if (depsToolname.equals(dp.getMetadata().getTool())) {
        assert keep == null;
        keep = dp;
      }
    }
    if (keep == null) {
      if (logFail) {
        Log.info("didn't find DependencyParse with tool name: " + depsToolname + " in Sentence " + s.getUuid());
        for (DependencyParse dp : s.getTokenization().getDependencyParseList())
          System.out.println(dp.getMetadata().getTool());
      }
      return false;
    }
    
    // Output the facts
    for (Dependency edge : keep.getDependencyList()) {
      write("x", relationName, edge.getGov(), edge.getDep(), edge.getEdgeType());
    }
    return true;
  }
  
  public boolean writeCons(String consToolname, Sentence s, String relationName, boolean logFail) throws IOException {
//    Log.info("looking for " + consToolname + " in " + s.getUuid());
    Parse keep = null;
    for (Parse p : s.getTokenization().getParseList()) {
      if (consToolname.equals(p.getMetadata().getTool())) {
        assert keep == null;
        keep = p;
      }
    }
    if (keep == null) {
      if (logFail) {
        Log.info("didn't find constituency Parse with tool name: " + consToolname + " in Sentence " + s.getUuid());
        for (DependencyParse dp : s.getTokenization().getDependencyParseList())
          System.out.println(dp.getMetadata().getTool());
      }
      return false;
    }
    
    // Build an ad-hoc tree structure
    Map<Integer, Integer> id2parent = new HashMap<>();
    Map<Integer, Constituent> id2node = new HashMap<>();
    for (Constituent c : keep.getConstituentList()) {
      for (int child : c.getChildList()) {
        Object old = id2parent.put(child, c.getId());
        assert old == null;
      }
      Object old = id2node.put(c.getId(), c);
        assert old == null;
    }
    // Output the facts
    for (Constituent c : keep.getConstituentList()) {
      // Compute the head token by descending the tree
      BitSet seen = new BitSet();
      Constituent head = c;
      while (width(head) > 1 && head.isSetHeadChildIndex()) {
        if (seen.get(head.getId()))
          throw new RuntimeException("loop! " + keep);
        seen.set(head.getId());
        int h = head.getHeadChildIndex();
        if (h < 0)
          break;
        int hi = head.getChildList().get(h);
        head = keep.getConstituentList().get(hi);
      }
      int headToken = c.getEnding() - 1;
      if (width(head) > 1)
        Log.warn("can't find head for constituent in sentence " + s.getUuid().getUuidString());

      int parent = id2parent.getOrDefault(c.getId(), -1);
      Span extent = Span.getSpan(c.getStart(), c.getEnding());
      write("x", relationName, c.getId(), parent, headToken, extent.shortString(), c.getTag());
    }
    return true;
  }
  
  private static int width(Constituent c) {
    return c.getEnding() - c.getStart();
  }

  public void writeOneDocPerSentence(Communication c) throws IOException {
    int tokenOffset = 0;
    for (Section section : c.getSectionList()) {
      int secTokStart = tokenOffset;
      if (!section.isSetSentenceList())
        continue;
      for (Sentence sentence : section.getSentenceList()) {
        int sentTokStart = tokenOffset;
        
        // startdoc
        assert c.getUuid().getUuidString().indexOf('/') < 0;
        assert sentence.getUuid().getUuidString().indexOf('/') < 0;
        String arg0 = c.getUuid().getUuidString() + "/" + sentence.getUuid().getUuidString();
        assert arg0.split("\\s+").length == 1;
        write("startdoc", arg0);

        // word2
        // tokloc3
        Tokenization t = sentence.getTokenization();
        tokenOffset = 0;
        for (Token tk : t.getTokenList().getTokenList()) {
          String word = tk.getText();
          assert word.split("\\s+").length == 1 : "word with whitespace? requires escaping: " + word;

          write("x", "word2", tokenOffset, word);

          if (writeTokloc) {
            TextSpan ts = tk.getTextSpan();
            write("x", "tokloc3", tokenOffset, ts.getStart(), ts.getEnding());
          }

          tokenOffset++;
        }
        
        if (writeSegment4) {
          int sentTokEnd = tokenOffset;
          write("x", "segment4", t.getUuid().getUuidString(), "sentence", sentTokStart, sentTokEnd);
        }

        // pos2, lemma2
        boolean logFail = true;
        writeTags("Stanford CoreNLP", "POS", sentence, "pos2", logFail);
        writeTags("Stanford CoreNLP", "LEMMA", sentence, "lemma2", logFail);
        
        // dsyn3-*
        writeDeps("Stanford CoreNLP basic", sentence, "dsyn3-basic", logFail);
        writeDeps("Stanford CoreNLP col", sentence, "dsyn3-col", logFail);
        writeDeps("Stanford CoreNLP col-CC", sentence, "dsyn3-colcc", logFail);
        
        // csyn5-*
        writeCons("Stanford CoreNLP", sentence, "csyn5-stanford", logFail);

//        // tag3
//        for (TokenTagging tt : t.getTokenTaggingList()) {
//          String tp = tt.getTaggingType().replace(' ', '_');
//          for (TaggedToken tti : tt.getTaggedTokenList())
//            write("x", "tag3", tp, tti.getTokenIndex(), tti.getTag());
//        }
      }
      if (writeSegment4) {
        int secTokEnd = tokenOffset;
        write("x", "segment4", section.getUuid().getUuidString(), "section", secTokStart, secTokEnd);
      }
    }

  }
  
  /**
   * TODO This is not really compatible with sentence-level startdoc deliminations!
   * I don't think anyone is using this now though.
   */
  public void writeTacSfSituationMentions(Communication c) throws IOException {
    if (c.getSituationMentionSetList() != null) {
      for (SituationMentionSet sms : c.getSituationMentionSetList()) {
//        String tool = sms.getMetadata().getTool().replace(' ', '_');
        for (SituationMention sm : sms.getMentionList()) {

          // TAC SF slot
          String slot = sm.getSituationKind();

//          // Trigger
//          TokenRefSequence predLoc = sm.getTokens();
//          if (predLoc == null) {
//            // This SituationMention doesn't have a trigger specified.
//            // It may still have arguments.
//          } else {
//            String predLocStr = predLoc.getTokenIndexList().get(0)
//                + "]" + predLoc.getTokenIndexList().get(predLoc.getTokenIndexListSize()-1);
//            if (predLoc.isSetAnchorTokenIndex())
//              predLocStr += "@" + predLoc.getAnchorTokenIndex();
//            String evType = "NA";
//            if (sm.isSetSituationKind())
//              evType = sm.getSituationKind();
//            if (sm.isSetSituationType())
//              evType += "/" + sm.getSituationType();
//            write("x", "event3", tool, predLocStr, evType);
//          }

          // Arguments
          for (MentionArgument ma : sm.getArgumentList()) {
            String k = ma.getRole();
            TokenRefSequence argLoc = ma.getTokens();
            String argLocStr = argLoc.getTokenIndexList().get(0)
                + "]" + argLoc.getTokenIndexList().get(argLoc.getTokenIndexListSize()-1);
            if (argLoc.isSetAnchorTokenIndex())
              argLocStr += "@" + argLoc.getAnchorTokenIndex();
            write("x", "sm-arg", slot, k, argLocStr, ma.getTokens().getTokenizationId().getUuidString());
//            write("x", "sm-arg", tool, k, argLocStr, ma.getTokens().getTokenizationId().getUuidString());
          }

          /*
           * If I want this to be useful, I need to find a baseline number (someone else system)
           * and figure out what assumptions they make upon inference (e.g. we get the document
           * with an in situ query and we can assume the filler is in the same document).
           */
          MentionArgument query = sm.getArgumentList().get(0);
          MentionArgument filler = sm.getArgumentList().get(1);
          String tok = query.getTokens().getTokenizationId().getUuidString();
          assert tok.equals(filler.getTokens().getTokenizationId().getUuidString());
          write("y", "tac-sf", slot, getSpan(query), getSpan(filler), tok);
        }
      }
    }
  }

  private static String getSpan(MentionArgument ma) {
    TokenRefSequence trs = ma.getTokens();
    int first = trs.getTokenIndexList().get(0);
    int last = trs.getTokenIndexList().get(trs.getTokenIndexListSize() - 1);
    return first + "-" + (last+1);
  }

  public static void main(String[] args) throws IOException {
    if (args.length != 2) {
      System.err.println("please provide:");
      System.err.println("1) an input Communication .tgz file");
      System.err.println("2) an output many-doc fact file");
      System.exit(-1);
    }
    int interval = 200;
    Timer tmRead = new Timer("communication-read", interval, true);
    Timer tmWrite = new Timer("communication-write", interval, true);
    File f = new File(args[0]);
    Log.info("reading raw Communications (expected *.tgz) from " + f.getPath());
    try (FileInputStream fis = new FileInputStream(f);
        ConcreteToRelations c2r = new ConcreteToRelations(new File(args[1]))) {
      TarGzArchiveEntryCommunicationIterator itr = new TarGzArchiveEntryCommunicationIterator(fis);
      while (itr.hasNext()) {
        tmRead.start();
        Communication c = itr.next();
        tmRead.stop();
        
        tmWrite.start();
        c2r.writeOneDocPerSentence(c);
        tmWrite.stop();
      }
    }
    Log.info("done");
  }
}
