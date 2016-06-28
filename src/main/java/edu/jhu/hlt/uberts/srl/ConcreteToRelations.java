package edu.jhu.hlt.uberts.srl;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.MentionArgument;
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
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.io.FactWriter;

/**
 * Given a {@link Communication}, output supervision in the form of a set of
 * {@link HypEdge}s in a text file format.
 *
 * AHH!!
 * These outputs (relations) can overlap!
 * I can easily think of various ways to output entity clusterings:
 * <mentionId> <clusterId>
 * <antecedentId> <referenceId>, assumed transitive (or not!)
 * <mentionLoc> <clusterId>
 * <antecedentLoc> <referenceLoc>, assumed transitive (or not!)
 * => There is no reason that you can't output both, and grep one out
 *  OR, more efficiently, take a list of relations you want output in this tool.
 *
 * @deprecated I'm going to start with task-specific adapters, e.g. {@link FNParseToRelations},
 * Keeping this around since its a good example
 *
 * @author travis
 */
public class ConcreteToRelations extends FactWriter {

  public ConcreteToRelations(File f) {
    super(f);
  }

  public static void writeDefs(BufferedWriter w) throws IOException {
    // TODO This doesn't handle "-<toolName>"
    w.write("def word2 <tokenIndex> <word>");
    w.newLine();
    w.write("def tag3 <taggingType> <tokenIndex> <tag> # derived from TokenTaggings");
    w.newLine();
    w.write("def dsyn3 <govTokIdx> <depTokIdx> <edgeLabel> # derived from DependencyParse");
    w.newLine();
    w.write("def csyn3 <startTokIdxIncl> <endTokIdxExcl> <constituentLabel> # derived from Parse");
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
    w.write("def tokloc3 <tokenIndex> <startCharIdxIncl> <endCharIdxExcl>");
    w.newLine();

    /*
     * Example transform, discrepancy between concrete-generation and uberts-consumption:
     * "x tag3 pos 0 NNP"
     * In concrete I just want to define TokenTagging => tag3 Relation
     * In uberts I want a short path 0:tokenIndex --pos2--> NNP:posTag, not 0:tokenIndex --tag3--> pos:tagType --backwards[tag3]--> NNP:tag
     */
  }

  public void writeData(Communication c) throws IOException {
    int tokenOffset = 0;
//    int charOffset = 0;
    for (Section section : c.getSectionList()) {
      int secTokStart = tokenOffset;
      for (Sentence sentence : section.getSentenceList()) {
        int sentTokStart = tokenOffset;

        // word2, tokloc3
        Tokenization t = sentence.getTokenization();
        for (Token tk : t.getTokenList().getTokenList()) {
          String word = tk.getText();
          assert word.split("\\s+").length == 1 : "word with whitespace? requires escaping: " + word;
          write("x", "word2", tokenOffset, word);
          TextSpan ts = tk.getTextSpan();
          write("x", "tokloc3", tokenOffset, ts.getStart(), ts.getEnding());
          tokenOffset++;
        }
        int sentTokEnd = tokenOffset;
        write("x", "segment4", t.getUuid().getUuidString(), "sentence", sentTokStart, sentTokEnd);

        // tag3
        for (TokenTagging tt : t.getTokenTaggingList()) {
          String tp = tt.getTaggingType().replace(' ', '_');
          for (TaggedToken tti : tt.getTaggedTokenList())
            write("x", "tag3", tp, tti.getTokenIndex(), tti.getTag());
        }
      }
      int secTokEnd = tokenOffset;
      write("x", "segment4", section.getUuid().getUuidString(), "section", secTokStart, secTokEnd);
    }

    if (c.getSituationMentionSetList() != null) {
      for (SituationMentionSet sms : c.getSituationMentionSetList()) {
        String tool = sms.getMetadata().getTool().replace(' ', '_');
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
    if (args.length != 1) {
      System.err.println("please provide a concrete *.tar.gz file");
      System.exit(-1);
    }
//    ConcreteToRelations c2r = new ConcreteToRelations();
    File f = new File(args[0]);
    System.out.println("reading raw Communications (expected *.tgz) from " + f.getPath());
    try (FileInputStream fis = new FileInputStream(f);
        ConcreteToRelations c2r = new ConcreteToRelations(new File("/tmp/slot-fill.facts"))) {
//        BufferedWriter w = new BufferedWriter(new OutputStreamWriter(System.out))) {
//        BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(new File("/tmp/slot-fill.facts"))))) {
      TarGzArchiveEntryCommunicationIterator itr = new TarGzArchiveEntryCommunicationIterator(fis);
      assert itr.hasNext();
//      c2r.writeDefs(w);
//      c2r.writeData(itr.next(), w);
      c2r.writeData(itr.next());
    }
  }
}
