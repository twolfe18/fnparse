package edu.jhu.hlt.fnparse.features.precompute;

import java.io.BufferedReader;
import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import edu.jhu.hlt.tutils.FileUtil;

/**
 * Implements the in-memory data structure mapping:
 *   (oldIntTemplate, oldIntFeature) => (newIntTemplate, newIntFeature)
 *
 * Reads the file format created by {@link BiAlphMerger} (6 column tsv).
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
    public Line(String line, boolean bialphLine) {
      set(line, bialphLine);
    }
    @Override
    public String toString() {
      return "(BiAlphLine " + line + ")";
    }
    public boolean isNull() {
      return line == null;
    }
    public void set(String line, boolean bialphLine) {
      this.line = line;
      if (line != null) {
        String[] toks = line.split("\t");
        int i = 0;
        newIntTemplate = Integer.parseInt(toks[i++]);
        newIntFeature = Integer.parseInt(toks[i++]);
        stringTemplate = toks[i++];
        stringFeature = toks[i++];
        if (bialphLine) {
          oldIntTemplate = Integer.parseInt(toks[i++]);
          oldIntFeature = Integer.parseInt(toks[i++]);
        } else {
          oldIntTemplate = -1;
          oldIntFeature = -1;
        }
        assert i == toks.length;
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
  private int[] newInt2MaxFeatureIndex;
  private int[] oldInt2NewIntTemplates;
  private int[][] oldInt2NewIntFeatures;
  private File file;

  public BiAlph(File f, boolean fIsBialph) {
    this.templateName2NewInt = new HashMap<>();
    this.newInt2TemplateName = new String[0];
    this.newInt2MaxFeatureIndex = new int[0];
    this.oldInt2NewIntTemplates = new int[0];
    this.oldInt2NewIntFeatures = new int[0][0];
    set(f, fIsBialph);
  }

  public File getSource() {
    return file;
  }

  private void ensureCapacity(Line l) {
    // old int
    if (l.oldIntTemplate >= 0) {
      int oldT = oldInt2NewIntTemplates.length;
      if (l.oldIntTemplate >= oldT) {
        int newSize = (int) (l.oldIntTemplate * 1.6 + 1);
        oldInt2NewIntTemplates = Arrays.copyOf(oldInt2NewIntTemplates, newSize);
        oldInt2NewIntFeatures = Arrays.copyOf(oldInt2NewIntFeatures, newSize);
        for (int i = oldT; i < oldInt2NewIntFeatures.length; i++)
          oldInt2NewIntFeatures[i] = new int[0];
      }
      int oldF = oldInt2NewIntFeatures[l.oldIntTemplate].length;
      if (l.oldIntFeature >= oldF) {
        int newSize = (int) (l.oldIntFeature * 1.6 + 1);
        oldInt2NewIntFeatures[l.oldIntTemplate] =
            Arrays.copyOf(oldInt2NewIntFeatures[l.oldIntTemplate], newSize);
      }
    }
    // new int
    int newT = newInt2TemplateName.length;
    if (l.newIntTemplate >= newT) {
      int newSize = (int) (l.newIntTemplate * 1.6 + 1);
      newInt2TemplateName = Arrays.copyOf(newInt2TemplateName, newSize);
      newInt2MaxFeatureIndex = Arrays.copyOf(newInt2MaxFeatureIndex, newSize);
    }
  }

  /**
   * @param fIsBiAlph if true f is interpretted as a bialph (6 col tsv),
   * otherwise f is interpretted as an alph (4 col tsv).
   */
  public void set(File f, boolean fIsBiAlph) {
    this.file = f;
    try (BufferedReader r = FileUtil.getReader(f)) {
      Line l = new Line(null, fIsBiAlph);
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        l.set(line, fIsBiAlph);
        assert (l.oldIntTemplate < 0) == (l.oldIntFeature < 0);

        ensureCapacity(l);

        if (l.oldIntTemplate >= 0) {
          oldInt2NewIntTemplates[l.oldIntTemplate] = l.newIntTemplate;
          oldInt2NewIntFeatures[l.oldIntTemplate][l.oldIntFeature] = l.newIntFeature;
        }

        Integer old = templateName2NewInt.put(l.stringTemplate, l.newIntTemplate);
        assert old == null || old.intValue() == l.newIntTemplate
          : l.stringTemplate + " maps to both old=" + old + " and new=" + l.newIntTemplate;
        newInt2TemplateName[l.newIntTemplate] = l.stringTemplate;
        if (l.newIntFeature > newInt2MaxFeatureIndex[l.newIntTemplate])
          newInt2MaxFeatureIndex[l.newIntTemplate] = l.newIntFeature;
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public int cardinalityOfNewTemplate(int newTemplateIndex) {
    return newInt2MaxFeatureIndex[newTemplateIndex] + 1;
  }

  public int mapTemplate(String templateName) {
    return templateName2NewInt.get(templateName);
  }

  public String lookupTemplate(int newTemplateIndex) {
    return newInt2TemplateName[newTemplateIndex];
  }

  public int mapTemplate(int oldTemplateIndex) {
    return oldInt2NewIntTemplates[oldTemplateIndex];
  }

  public int mapFeature(int oldTemplateIndex, int oldFeatureIndex) {
    return oldInt2NewIntFeatures[oldTemplateIndex][oldFeatureIndex];
  }
}
