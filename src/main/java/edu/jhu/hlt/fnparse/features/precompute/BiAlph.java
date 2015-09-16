package edu.jhu.hlt.fnparse.features.precompute;

import java.io.BufferedReader;
import java.io.File;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import edu.jhu.hlt.tutils.FileUtil;

/**
 * Implements the in-memory data structure mapping:
 *   (oldIntTemplate, oldIntFeature) => (newIntTemplate, newIntFeature)
 *
 * Reads the file format created by {@link AlphabetMerger}.
 *
 * @author travis
 */
public class BiAlph {

  /** When oldInt* isn't populated, -1 is used. */
  public static class Line {
    public String line;
    public int newIntTemplate;
    public int newIntFeature;
    public String stringTemplate;
    public String stringFeature;
    public int oldIntTemplate;
    public int oldIntFeature;
    public Line(String line) {
      set(line);
    }
    @Override
    public String toString() {
      return "(BiAlphLine " + line + ")";
    }
    public boolean isNull() {
      return line == null;
    }
    public void set(String line) {
      this.line = line;
      if (line != null) {
        String[] toks = line.split("\t");
        assert toks.length == 6;
        int i = 0;
        newIntTemplate = Integer.parseInt(toks[i++]);
        newIntFeature = Integer.parseInt(toks[i++]);
        stringTemplate = toks[i++];
        stringFeature = toks[i++];
        oldIntTemplate = Integer.parseInt(toks[i++]);
        oldIntFeature = Integer.parseInt(toks[i++]);
      }
    }
    public static Comparator<Line> BY_TEMPLATE_STR_FEATURE_STR = new Comparator<Line>() {
      @Override
      public int compare(Line o1, Line o2) {
        int c1 = o1.stringTemplate.compareTo(o2.stringTemplate);
        if (c1 != 0)
          return c1;
        int c2 = o1.stringFeature.compareTo(o2.stringFeature);
        return c2;
      }
    };
  }

  // Not a full alphabet (over (template,features)), so should fit in memory easily
  private Map<String, Integer> templateName2NewInt;
  private String[] newInt2TemplateName;
  private int[] maxFeatureIndex;      // indexed by new template index, stores max new feature index
  private int[] templatePermutation;
  private int[][] featurePermutation;
  private File file;

  public BiAlph(File f) {
    set(f);
  }

  public File getSource() {
    return file;
  }

  public void set(File f) {
    this.file = f;
    try (BufferedReader r = FileUtil.getReader(f)) {
      Line l = new Line(null);
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        l.set(line);
        assert (l.oldIntTemplate < 0) == (l.oldIntFeature < 0);
        // TODO skip when l.oldIntTemplate < 0?
        templatePermutation[l.oldIntTemplate] = l.newIntTemplate;
        featurePermutation[l.oldIntTemplate][l.oldIntFeature] = l.newIntFeature;
        Integer old = templateName2NewInt.put(l.stringTemplate, l.newIntTemplate);
        assert old == null || old == l.newIntFeature;
        newInt2TemplateName[l.newIntTemplate] = l.stringTemplate;
        if (l.newIntFeature > maxFeatureIndex[l.newIntTemplate])
          maxFeatureIndex[l.newIntTemplate] = l.newIntFeature;
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public int cardinalityOfNewTemplate(int newTemplateIndex) {
    return maxFeatureIndex[newTemplateIndex] + 1;
  }

  public int mapTemplate(String templateName) {
    return templateName2NewInt.get(templateName);
  }

  public String lookupTemplate(int newTemplateIndex) {
    return newInt2TemplateName[newTemplateIndex];
  }

  public int mapTemplate(int oldTemplateIndex) {
    return templatePermutation[oldTemplateIndex];
  }

  public int mapFeature(int oldTemplateIndex, int oldFeatureIndex) {
    return featurePermutation[oldTemplateIndex][oldFeatureIndex];
  }
}
