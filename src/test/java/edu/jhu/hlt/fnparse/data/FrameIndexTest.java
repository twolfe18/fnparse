
package edu.jhu.hlt.fnparse.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;
import edu.jhu.hlt.tutils.data.WordNetPosUtil;

public class FrameIndexTest {

  @Test
  public void basic() {

    long start = System.currentTimeMillis();
    FrameIndex frameIndex = FrameIndex.getFrameNet();
    List<Frame> allFrames = frameIndex.allFrames();
    Map<String, Frame> frameMap = frameIndex.nameToFrameMap();
    List<Frame> allFrames2 = frameIndex.allFrames();
    assertEquals(allFrames.size(), frameMap.size());
    assertEquals(allFrames.size(), allFrames2.size());
    System.out.println(allFrames.size());
    assertEquals(allFrames.size(), (FrameIndex.framesInFrameNet + 1));	// +1 for nullFrame
    long time = System.currentTimeMillis() - start;

    Set<Integer> ids = new HashSet<Integer>();
    int max = -1;
    for(Frame f : allFrames) {
      System.out.println(f);
      assertTrue(ids.add(f.getId()));
      if(f.getId() > max)
        max = f.getId();

      assertTrue(f.numLexicalUnits() >= 0);
      assertTrue(f.numRoles() >= 0);
      for(int i=0; i<f.numLexicalUnits(); i++) {
        LexicalUnit lu = f.getLexicalUnit(i);
        String msg = String.format("%s does not have a POS in the Lexical Unit FrameNet conversion tagset", lu.toString());
        assertTrue(msg, WordNetPosUtil.getFrameNetPosToPennPrefixesMap().containsKey(lu.pos));
      }
    }
    System.out.printf("reading %d frames took %.2f sec\n", allFrames.size(), time/1000d);
    assertEquals(max, allFrames.size()-1);
  }

  @Test
  public void listFNposTags() {
    Set<String> all = new HashSet<String>();
    for(Frame f : FrameIndex.getFrameNet().allFrames())
      for(int i=0; i<f.numLexicalUnits(); i++)
        all.add(f.getLexicalUnit(i).pos);
    System.out.println("\nAll POS tags used in lexical units for frames:");
    for(String s : all)
      System.out.println(s);

    // lets get all words listed as ART = article
    all.clear();
    for(Frame f : FrameIndex.getFrameNet().allFrames())
      for(int i=0; i<f.numLexicalUnits(); i++)
        if(f.getLexicalUnit(i).pos.equalsIgnoreCase("ART"))
          all.add(f.getLexicalUnit(i).word);
    System.out.println("\nAll words associated with ART:");
    for(String s : all)
      System.out.println(s);	// some of these are listed as PDT ("predeterminer") in Penn

  }
}
