package edu.jhu.hlt.fnparse.features.precompute;

import java.io.BufferedReader;
import java.io.File;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.TimeMarker;

/**
 * Implements the in-memory data structure mapping:
 *   (oldIntTemplate, oldIntFeature) => (newIntTemplate, newIntFeature)
 *
 * Since this class only stores strings for templates and not features,
 * this can relatively easily fit in memory, event with 10M+ features.
 *
 * Reads the file format created by {@link BiAlphMerger} (6 column TSV).
 *
 * @author travis
 */
public class BiAlph implements Serializable {
  private static final long serialVersionUID = -1785815794281505707L;

  public enum LineMode {
    BIALPH,       // 6 col tsv
    ALPH,         // 4 col tsv, no old int (template,feature)
    ALPH_AS_TRIVIAL_BIALPH,   // same as ALPH, but sets old=new (template,feature)
  }

  /** When oldInt* isn't populated, -1 is used. */
  public static class Line {

    /** Sorts first by stringTemplate, then stringFeature */
    public static Comparator<Line> BY_STRINGS = new Comparator<Line>() {
      @Override
      public int compare(Line o1, Line o2) {
        int c = o1.stringTemplate.compareTo(o2.stringTemplate);
        if (c != 0)
          return c;
        return o1.stringFeature.compareTo(o2.stringFeature);
      }
    };

    public String line;
    public int newIntTemplate;
    public int newIntFeature;
    public String stringTemplate;
    public String stringFeature;
    public int oldIntTemplate;
    public int oldIntFeature;

    public Line(String line, LineMode lineMode) {
      set(line, lineMode);
    }
    @Override
    public String toString() {
      return "(BiAlphLine " + line + ")";
    }
    public boolean isNull() {
      return line == null;
    }
    public void set(String line, LineMode mode) {
      this.line = line;
      if (line != null) {
        String[] toks = line.split("\t");
        int i = 0;
        newIntTemplate = Integer.parseInt(toks[i++]);
        newIntFeature = Integer.parseInt(toks[i++]);
        stringTemplate = toks[i++];
        stringFeature = toks[i++];
        switch (mode) {
        case BIALPH:
          oldIntTemplate = Integer.parseInt(toks[i++]);
          oldIntFeature = Integer.parseInt(toks[i++]);
          break;
        case ALPH:
          oldIntTemplate = -1;
          oldIntFeature = -1;
          break;
        case ALPH_AS_TRIVIAL_BIALPH:
          oldIntTemplate = newIntTemplate;
          oldIntFeature = newIntFeature;
          break;
        default:
          throw new RuntimeException("unknow mode: " + mode);
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

  public BiAlph(File f, LineMode lineMode) {
    this.templateName2NewInt = new HashMap<>();
    this.newInt2TemplateName = new String[0];
    this.newInt2MaxFeatureIndex = new int[0];
    this.oldInt2NewIntTemplates = new int[0];
    this.oldInt2NewIntFeatures = new int[0][0];
    set(f, lineMode);
  }

  public int getUpperBoundOnNumTemplates() {
    return newInt2MaxFeatureIndex.length;
//    int max = 0;
//    for (int i = 0; i < newInt2MaxFeatureIndex.length; i++)
//      if (newInt2MaxFeatureIndex[i] > 0)
//        max = i;
//    return max + 1;
  }
  public int getUpperBoundOnNumFeatures(int template) {
    return newInt2MaxFeatureIndex[template] + 1;
  }

  public File getSource() {
    return file;
  }

  public int[] makeTemplate2Cardinality() {
    int[] card = Arrays.copyOf(newInt2MaxFeatureIndex, newInt2MaxFeatureIndex.length);
    for (int i = 0; i < card.length; i++)
      card[i]++;
    return card;
  }

  public int[] copyOfWithPadding(int[] array, int newSize, int paddingValue) {
    int oldSize = array.length;
    int[] newArray = Arrays.copyOf(array, newSize);
    for (int i = oldSize; i < newSize; i++)
      newArray[i] = paddingValue;
    return newArray;
  }

  private void ensureCapacity(Line l) {
    // old int
    if (l.oldIntTemplate >= 0) {
      int oldT = oldInt2NewIntTemplates.length;
      if (l.oldIntTemplate >= oldT) {
        int newSize = (int) (l.oldIntTemplate * 1.6 + 1);
        oldInt2NewIntTemplates = copyOfWithPadding(oldInt2NewIntTemplates, newSize, -1);
        oldInt2NewIntFeatures = Arrays.copyOf(oldInt2NewIntFeatures, newSize);
        for (int i = oldT; i < oldInt2NewIntFeatures.length; i++)
          oldInt2NewIntFeatures[i] = new int[0];
      }
      int oldF = oldInt2NewIntFeatures[l.oldIntTemplate].length;
      if (l.oldIntFeature >= oldF) {
        int newSize = (int) (l.oldIntFeature * 1.6 + 1);
        oldInt2NewIntFeatures[l.oldIntTemplate] =
            copyOfWithPadding(oldInt2NewIntFeatures[l.oldIntTemplate], newSize, -1);
      }
    }
    // new int
    int newT = newInt2TemplateName.length;
    if (l.newIntTemplate >= newT) {
      int newSize = (int) (l.newIntTemplate * 1.6 + 1);
      newInt2TemplateName = Arrays.copyOf(newInt2TemplateName, newSize);
      newInt2MaxFeatureIndex = copyOfWithPadding(newInt2MaxFeatureIndex, newSize, -1);
    }
  }

  /**
   * Read a bialph out of a file.
   */
  public void set(File f, LineMode lineMode) {
    Log.info("loading bialph from " + f.getPath() + " lineMode=" + lineMode);
    TimeMarker tm = new TimeMarker();
    this.file = f;
    int processed = 0;
    try (BufferedReader r = FileUtil.getReader(f)) {
      Line l = new Line(null, lineMode);
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        processed++;
        l.set(line, lineMode);
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

        if (tm.enoughTimePassed(15)) {
          Log.info("processed " + tm.numMarks()
            + " lines in " + tm.secondsSinceFirstMark() + " seconds, "
            + Describe.memoryUsage());
        }
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    Log.info("done, processed " + processed + " lines");
  }

  public int cardinalityOfNewTemplate(int newTemplateIndex) {
    return newInt2MaxFeatureIndex[newTemplateIndex] + 1;
  }

  public int mapTemplate(String templateName) {
    Integer t = templateName2NewInt.get(templateName);
    if (t == null) {
      Log.warn(templateName + " is not in map of size " + templateName2NewInt.size());
      return -1;
    }
    return t;
  }

  public String lookupTemplate(int newTemplateIndex) {
    if (newTemplateIndex >= newInt2TemplateName.length)
      return null;
    return newInt2TemplateName[newTemplateIndex];
  }
  public String[] lookupTemplates(int[] newTemplateIndices) {
    String[] s = new String[newTemplateIndices.length];
    for (int i = 0; i < s.length; i++)
      s[i] = lookupTemplate(newTemplateIndices[i]);
    return s;
  }

  public int mapTemplate(int oldTemplateIndex) {
    if (oldTemplateIndex >= oldInt2NewIntTemplates.length)
      return -1;
    return oldInt2NewIntTemplates[oldTemplateIndex];
  }

  public int mapFeature(int oldTemplateIndex, int oldFeatureIndex) {
    if (oldTemplateIndex >= oldInt2NewIntFeatures.length)
      return -1;
    if (oldFeatureIndex >= oldInt2NewIntFeatures[oldTemplateIndex].length)
      return -1;
    return oldInt2NewIntFeatures[oldTemplateIndex][oldFeatureIndex];
  }


  public static void main(String[] args) {
    if (args.length == 0) {
      System.err.println("usage: <command> <args>");
      System.err.println("       jser <inputBialph> <inputBialphLineMode> <outputJserBialph>");
      return;
    }
    if ("jser".equalsIgnoreCase(args[0])) {
      File f = new File(args[1]);
      LineMode m = LineMode.valueOf(args[2]);
      File out = new File(args[3]);
      BiAlph b = new BiAlph(f, m);
      FileUtil.serialize(b, out);
    } else {
      System.err.println("unknown command: " + args[0]);
    }
  }
}
