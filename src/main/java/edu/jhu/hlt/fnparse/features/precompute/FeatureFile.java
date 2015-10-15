package edu.jhu.hlt.fnparse.features.precompute;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation.Feature;
import edu.jhu.hlt.fnparse.features.precompute.InformationGainProducts.BaseTemplates;

public class FeatureFile {

  public static class TemplateExtraction {
    public int template;
    public int[] features;
    public TemplateExtraction(int t, List<Feature> features) {
      template = t;
      this.features = new int[features.size()];
      for (int i = 0; i < this.features.length; i++) {
        Feature tf = features.get(i);
        assert tf.template == t;
        this.features[i] = tf.feature;
      }
    }

    /**
     * Converts features from ints to ProductIndex
     */
    public ProductIndex[] featureToProductIndex() {
      ProductIndex[] pi = new ProductIndex[features.length];
      for (int i = 0; i < pi.length; i++)
        pi[i] = new ProductIndex(features[i]);
      return pi;
    }
  }

  /**
   * This does group by template, which {@link BaseTemplates} doesn't do.
   */
  public static class Line {
    private boolean sorted;
    private String line;
    private String[] tokenized;
    private List<Feature> features;

    public Line(String line, boolean sorted) {
      features = new ArrayList<>();
      init(line, sorted);
    }

    public void init(String line, boolean sorted) {
      this.sorted = sorted;
      this.line = line;
      tokenized = null;
      features.clear();
    }

    private void tokenize() {
      tokenized = line.split("\t");
      for (int i = 5; i < tokenized.length; i++) {
        String[] tfs = tokenized[i].split(":");
        assert tfs.length == 2;
        int t = Integer.parseInt(tfs[0]);
        int f = Integer.parseInt(tfs[1]);
        features.add(new Feature(null, t, null, f, 1));
      }
    }

    public String getSentenceId() {
      if (tokenized == null)
        tokenize();
      return tokenized[1];
    }

    public int[] getRoles(boolean addOne) {
      if (tokenized == null)
        tokenize();
      String[] t = tokenized[4].split(",");
      int[] roles = new int[t.length];
      for (int i = 0; i < t.length; i++)
        roles[i] = Integer.parseInt(t[i]) + (addOne ? 1 : 0);
      return roles;
    }

    public List<TemplateExtraction> groupByTemplate() {
      if (tokenized == null)
        tokenize();
      if (!sorted)
        Collections.sort(features, Feature.BY_TEMPLATE_IDX);
      List<TemplateExtraction> out = new ArrayList<>();
      int curT = -1;
      List<Feature> cur = new ArrayList<>();
      for (int i = 0; i < features.size(); i++) {
        Feature f = features.get(i);
        if (f.template != curT) {
          if (cur.size() > 0)
            out.add(new TemplateExtraction(curT, cur));
          cur.clear();
          curT = f.template;
        }
        cur.add(f);
      }
      return out;
    }

    public List<Feature> getFeatures() {
      if (tokenized == null)
        tokenize();
      return features;
    }
  }

}
