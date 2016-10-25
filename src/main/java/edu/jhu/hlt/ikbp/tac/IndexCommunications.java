package edu.jhu.hlt.ikbp.tac;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.EntityMention;
import edu.jhu.hlt.concrete.EntityMentionSet;
import edu.jhu.hlt.concrete.Section;
import edu.jhu.hlt.concrete.Sentence;
import edu.jhu.hlt.concrete.Token;
import edu.jhu.hlt.concrete.serialization.iterators.TarGzArchiveEntryCommunicationIterator;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.prim.util.Lambda.FnIntFloatToFloat;
import edu.jhu.prim.vector.IntFloatUnsortedVector;

/**
 * Produces:
 * 
 * Tables I want:
 * A: ngram, nerType, EntityMention UUID
 * B: Communication UUID, term, count (top K pairs sorted by tf-idf weight)
 * C: EntityMention UUID, Communication UUID
 * D: term|ngram:CityHash32, string
 * 
 * Requires reducing: B (sort of), D
 *
 * @author travis
 */
public class IndexCommunications implements AutoCloseable {
  
  public static final Charset UTF8 = Charset.forName("UTF-8");

  // Given a word, if it's not in this approx-set, then you definitely haven't seen it
  private BloomFilter<String> seenTerms;
  
  private BufferedWriter w_nerNgrams;   // hashedNgram, nerType, EntityMention UUID
  private BufferedWriter w_termDoc;     // count, hashedTerm, Communication UUID
  private BufferedWriter w_termHash;    // hashedTerm, term
  private BufferedWriter w_mentionLocs;   // EntityMention UUID, Communication UUUID, Communication id
  
  private HashFunction hash;

  private boolean outputTfIdfTerms = false;
  
  private long n_doc = 0, n_tok = 0, n_ent = 0, n_termWrites = 0, n_termHashes = 0;
  private int err_ner = 0;

  public IndexCommunications(File nerNgrams, File termDoc, File termHash, File mentionLocs) {
    double falsePosProb = 0.001;
    int expectedInsertions = 1<<20;
    seenTerms = BloomFilter.create(Funnels.stringFunnel(UTF8), expectedInsertions, falsePosProb);
    hash = Hashing.murmur3_32();
    try {
      w_nerNgrams = FileUtil.getWriter(nerNgrams);
      w_termDoc = FileUtil.getWriter(termDoc);
      w_termHash = FileUtil.getWriter(termHash);
      w_mentionLocs = FileUtil.getWriter(mentionLocs);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
  
  public void observe(Communication c) throws IOException {
    new AddNerTypeToEntityMentions(c);
    n_doc++;
    
    // EntityMention ngrams
    int ngrams = 6;
    if (c.isSetEntityMentionSetList()) {
      for (EntityMentionSet ems : c.getEntityMentionSetList()) {
        for (EntityMention em : ems.getMentionList()) {
          
          if ("O".equals(em.getEntityType()))
            continue;
          int ttypes = em.getEntityType().split(",").length;
          if (ttypes > 1)
            continue;
          
          w_mentionLocs.write(em.getUuid().getUuidString());
          w_mentionLocs.write('\t');
          w_mentionLocs.write(c.getUuid().getUuidString());
          w_mentionLocs.write('\t');
          w_mentionLocs.write(c.getId());
          w_mentionLocs.newLine();

          n_ent++;
          for (String ngram : ScanCAGForMentions.ngrams(em.getText(), ngrams)) {
            if (!em.isSetEntityType() || em.getEntityType().indexOf('\t') >= 0) {
              err_ner++;
              continue;
            }
            int i = hash(ngram);
            w_nerNgrams.write(Integer.toUnsignedString(i));
            w_nerNgrams.write('\t');
            w_nerNgrams.write(em.getEntityType());
            w_nerNgrams.write('\t');
            w_nerNgrams.write(em.getUuid().getUuidString());
            w_nerNgrams.newLine();
          }
        }
      }
    }
    
    // Terms
    List<String> terms = terms(c);
    if (outputTfIdfTerms) {

      // need to read-in IDF table
      throw new RuntimeException("implement me");

    } else {
      
      IntFloatUnsortedVector tf = new IntFloatUnsortedVector(terms.size());
      for (String t : terms) {
        n_tok++;
        int i = hash(t);
        tf.add(i, 1);
      }
      // apply calls compact
      tf.apply(new FnIntFloatToFloat() {
        @Override public float call(int arg0, float arg1) {
          try {
            // count, word, doc
            w_termDoc.write(Integer.toUnsignedString((int) arg1));
            w_termDoc.write('\t');
            w_termDoc.write(Integer.toUnsignedString(arg0));
            w_termDoc.write('\t');
            w_termDoc.write(c.getUuid().getUuidString());
            w_termDoc.newLine();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
          return arg1;
        }
      });
    }
  }
  
  private int hash(String t) {
    n_termHashes++;
    int i = hash.hashString(t, UTF8).asInt();
    if (!seenTerms.mightContain(t)) {
      n_termWrites++;
      seenTerms.put(t);
      try {
        w_termHash.write(Integer.toUnsignedString(i));
        w_termHash.write('\t');
        w_termHash.write(t);
        w_termHash.newLine();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
    return i;
  }
  
  private static List<String> terms(Communication c) {
    List<String> t = new ArrayList<>(256);
    if (c.isSetSectionList()) {
      for (Section section : c.getSectionList()) {
        if (section.isSetSentenceList()) {
          for (Sentence sentence : section.getSentenceList()) {
            for (Token tok : sentence.getTokenization().getTokenList().getTokenList()) {
              t.add(tok.getText().toLowerCase());
            }
          }
        }
      }
    }
//    int ngrams = 5;
//    if (c.isSetEntityMentionSetList()) {
//      for (EntityMentionSet ems : c.getEntityMentionSetList()) {
//        for (EntityMention em : ems.getMentionList()) {
//          for (String ngram : ScanCAGForMentions.ngrams(em.getText(), ngrams)) {
//            t.add("EntityMention-" + em.getEntityType() + "-" + ngram);
//          }
//        }
//      }
//    }
    return t;
  }

  @Override
  public void close() throws Exception {
    w_nerNgrams.close();
    w_termDoc.close();
    w_termHash.close();
    w_mentionLocs.close();
  }
  
  @Override
  public String toString() {
    return String.format("(IC n_doc=%d n_tok=%d n_ent=%d err_ner=%d n_termHashes=%d n_termWrites=%d)",
        n_doc, n_tok, n_ent, err_ner, n_termHashes, n_termWrites);
  }
  
  public static void main(String[] args) throws Exception {
//    File f = new File("data/concretely-annotated-gigaword/sample-frank-oct16/nyt_eng_200909.tar.gz");
//    File nerNgram = new File("/tmp/nerNgrams.txt.gz");
//    File termDoc = new File("/tmp/termDoc.txt.gz");
//    File termHash = new File("/tmp/termHash.txt.gz");
//    File mentionLocs = new File("/tmp/mentionLocs.txt.gz");
    
    if (args.length != 2) {
      System.err.println("please provied:");
      System.err.println("1) an input directory containing annotated .tar.gz Communication files");
      System.err.println("2) a directory to put output in");
      return;
    }
    File commDir = new File(args[0]);
    File outputDir = new File(args[1]);

    File nerNgram = new File(outputDir, "nerNgrams.txt.gz");
    File termDoc = new File(outputDir, "termDoc.txt.gz");
    File termHash = new File(outputDir, "termHash.txt.gz");
    File mentionLocs = new File(outputDir, "mentionLocs.txt.gz");

    TimeMarker tm = new TimeMarker();
    try (IndexCommunications ic = new IndexCommunications(nerNgram, termDoc, termHash, mentionLocs)) {
      for (File f : commDir.listFiles()) {
        if (!f.getName().toLowerCase().endsWith(".tar.gz"))
          continue;
        Log.info("reading " + f.getName());
        try (InputStream is = new FileInputStream(f);
            TarGzArchiveEntryCommunicationIterator iter = new TarGzArchiveEntryCommunicationIterator(is)) {
          while (iter.hasNext()) {
            Communication c = iter.next();
            ic.observe(c);
            if (tm.enoughTimePassed(10))
              Log.info(ic + "\t" + c.getId());
          }
        }
      }
    }
    Log.info("done");
  }
}
