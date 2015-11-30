package edu.jhu.hlt.fnparse.inference.stages;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.log4j.Logger;

/**
 * takes a List<StageDatum> and implements FgExamleList
 * 
 * @author travis
 */
public class StageDatumExampleList<I, O> {
  public static final Logger LOG = Logger.getLogger(StageDatumExampleList.class);

  public interface LFgExample {
  }

  private final List<StageDatum<I, O>> data;
  private LFgExample[] cache;

  // TODO
  // 1) add an option to see if i can flat out just fit everything in memory
  // 2) try sorting items by factor graph size (or better yet, memory usage),
  //    and store the smallest ones up until you hit a budget. big ones need to
  //    be re-computed.

  public StageDatumExampleList(List<StageDatum<I, O>> data) {
    this(data, false);
  }

  public StageDatumExampleList(List<StageDatum<I, O>> data, boolean keepAllInMemory) {
    this.data = data;
    if (keepAllInMemory)
      cache = new LFgExample[data.size()];
  }

  public Iterator<LFgExample> iterator() {
    return new Iterator<LFgExample>() {
      private Iterator<StageDatum<I, O>> iter = data.iterator();
      @Override
      public boolean hasNext() {
        return iter.hasNext();
      }
      @Override
      public LFgExample next() {
        return iter.next().getExample();
      }
      @Override
      public void remove() {
        throw new UnsupportedOperationException();
      }
    };
  }

  private static int inMem = 0;

  public LFgExample get(int index) {
    if (cache != null) {
      if (cache[index] == null) {
        cache[index] = data.get(index).getExample();
        inMem++;
        LOG.info("[get] inMem=" + inMem);
      }
      return cache[index];
    } else {
      return data.get(index).getExample();
    }
  }

  public StageDatum<I, O> getStageDatum(int index) {
    return data.get(index);
  }

  public List<O> decodeAll() {
    List<O> out = new ArrayList<>();
    for (StageDatum<I, O> d : data)
      out.add(d.getDecodable().decode());
    return out;
  }

  public List<StageDatum<I, O>> getStageData() {
    return data;
  }

  public int size() {
    return data.size();
  }
}
