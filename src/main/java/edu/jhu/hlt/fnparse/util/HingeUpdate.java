package edu.jhu.hlt.fnparse.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;

import edu.jhu.hlt.fnparse.rl.params.HasUpdate;

public interface HingeUpdate extends HasUpdate {

  public double violation();


  public static final HingeUpdate NONE = new HingeUpdate() {
    @Override
    public void getUpdate(double[] addTo, double scale) {
      // no-op
    }
    @Override
    public double violation() {
      return 0d;
    }
  };

  /**
   * Averages
   */
  public static class Batch implements HingeUpdate {
    private Collection<HingeUpdate> elements;
    public Batch(HingeUpdate... elements) {
      this.elements = Arrays.asList(elements);
    }
    @SuppressWarnings("unchecked")
    public <T extends HingeUpdate> Batch(Collection<T> elements) {
      if (elements.size() == 0)
        throw new IllegalArgumentException();
      this.elements = (Collection<HingeUpdate>) elements;
    }
    public Batch() {
      this.elements = new ArrayList<>();
    }
    public void add(HingeUpdate u) {
      this.elements.add(u);
    }
    @Override
    public void getUpdate(double[] addTo, double scale) {
      assert elements.size() > 0;
      double s = scale / elements.size();
      for (HasUpdate u : elements)
        u.getUpdate(addTo, s);
    }
    @Override
    public double violation() {
      assert elements.size() > 0;
      double v = 0d;
      for (HingeUpdate u : elements)
        v += u.violation();
      return v / elements.size();
    }
  }

}
