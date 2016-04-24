package edu.jhu.hlt.fnparse.features.precompute;

import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;

import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.serialization.iterators.TarGzArchiveEntryCommunicationIterator;
import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.data.propbank.ParsePropbankData;
import edu.jhu.hlt.fnparse.data.propbank.PropbankReader;
import edu.jhu.hlt.fnparse.datatypes.ConstituencyParse;
import edu.jhu.hlt.fnparse.datatypes.DependencyParse;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.features.FeatureIGComputation;
import edu.jhu.hlt.fnparse.features.TemplateContext;
import edu.jhu.hlt.fnparse.features.TemplatedFeatures.Template;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.hlt.fnparse.pruning.DeterministicRolePruning.Mode;
import edu.jhu.hlt.fnparse.rl.Action;
import edu.jhu.hlt.fnparse.rl.ActionType;
import edu.jhu.hlt.fnparse.rl.State;
import edu.jhu.hlt.fnparse.rl.rerank.Reranker;
import edu.jhu.hlt.fnparse.util.FrameRolePacking;
import edu.jhu.hlt.tutils.ConcreteDocumentMapping;
import edu.jhu.hlt.tutils.ConcreteToDocument;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.Document;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.IntTrip;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiAlphabet;
import edu.jhu.hlt.tutils.ShardUtils;
import edu.jhu.hlt.tutils.ShardUtils.Shard;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.SpanPair;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.hlt.tutils.data.BrownClusters;
import edu.jhu.hlt.tutils.hash.Hash;
import edu.jhu.hlt.tutils.ling.Language;
import edu.jhu.prim.bimap.IntObjectBimap;
import edu.mit.jwi.IRAMDictionary;

/**
 * Compute features ahead of time. Only outputs basic templates, not products,
 * which can be computed on the fly from the basic/kernel features.
 *
 * Produces two types of files:
 *  "features" which is the data below
 *  "alphabet" which is an int<->string bijection.
 *
 * (Note: this doesn't produce "bialphs", but they can be trivially created from
 *  alphabets, see {@link BiAlphMerger}).
 *
 * Columns of "feature" files:
 *  i = dataset id + sentence id
 *  t = (i,j) of target
 *  s = (i,j) of argument                     # forall span that is kept by DeterministicRolePruning
 *  k = index of role or -1 if y_{t,k,s} = 0  # assumes 0 or 1 roles per span w.r.t. a predicate
 *  f = feat*
 * where feat = template:feature:value
 *
 * NOTE: It turns out that some spans may have multiple roles assigned to them
 * in FramNet. For this data, the k column will actually be a comma-separated
 * list of roles (or -1).
 *
 * NOTE: Now k takes on values of (role,), (frame,role), and (frame,). The
 * values are stable across shards (so they do not need to be merged) and the
 * values are written out to WD/role-names.txt.gz.
 *
 * @author travis
 */
public abstract class FeaturePrecomputation {

  // TODO fill out the other columns
  public static final int ROLE_STRING_COLUMN = 4;
  public static final int ROLE_STRING_COLUMN_ROLE_MOD3 = 0;
  public static final int ROLE_STRING_COLUMN_FRAMEROLE_MOD3 = 1;
  public static final int ROLE_STRING_COLUMN_FRAME_MOD3 = 2;

  public static class Target {
    public static final String NO_DOC_ID = "noDocId".intern();
    public final String docId;      // must contain corpus id if theres more than one corpus
    public final String sentId;
    public final Span target;
    private final int hash;
    public Target(String sentId, Span target) {
      this(NO_DOC_ID, sentId, target);
    }
    public Target(String docId, String sentId, Span target) {
      this.docId = docId;
      this.sentId = sentId;
      this.target = target;
      this.hash = Hash.mix(sentId.hashCode(), Hash.mix(docId.hashCode(), target.hashCode()));
    }

    @Override
    public int hashCode() { return hash; }

    @Override
    public boolean equals(Object other) {
      if (other instanceof Target) {
        Target t = (Target) other;
        return hash == t.hash
            && target == t.target
            && sentId.equals(t.sentId)
            && docId.equals(t.docId);
      }
      return false;
    }
    public static String toLine(Target t) {
      return t.docId + "\t" + t.sentId + "\t" + t.target.shortString();
    }
    public static void toDos(Target t, DataOutputStream dos) throws IOException {
      dos.writeUTF(t.docId);
      dos.writeUTF(t.sentId);
      dos.writeInt(t.target.start);
      dos.writeInt(t.target.end);
    }
    public static Target fromDis(DataInputStream dis) throws IOException {
      String docId = dis.readUTF();
      String sentId = dis.readUTF();
      int start = dis.readInt();
      int end = dis.readInt();
      Span target = Span.getSpan(start, end);
      return new Target(docId, sentId, target);
    }
    public static Target fromLine(String line) {
      String[] ar = line.split("\t");
      if (ar.length == 3) {
        Span target = Span.inverseShortString(ar[2]);
        return new Target(ar[0], ar[1], target);
      }
      throw new RuntimeException(Arrays.toString(ar));
    }
  }

  /** Feature extraction value */
  public static class Feature {
    public final String templateName;
    public final int template;
    public final String featureName;
    public final int feature;
//    public final double value;
    public Feature(String templateName, int template, String featureName, int feature, double value) {
      this.templateName = templateName;
      this.template = template;
      this.featureName = featureName;
      this.feature = feature;
//      this.value = value;
    }
    public static Comparator<Feature> BY_TEMPLATE_IDX = new Comparator<Feature>() {
      @Override
      public int compare(Feature o1, Feature o2) {
        return o1.template - o2.template;
      }
    };
  }

  /** Holds a {@link Template} and often serves as an Alphabet */
  public static class TemplateAlphabet {
    public final Template template;
    public final String name;
    public final int index;
    public final edu.jhu.util.Alphabet<String> alph;
    public TemplateAlphabet(Template template, String name, int index) {
      this.template = template;
      this.name = name;
      this.index = index;
      this.alph = new edu.jhu.util.Alphabet<>();
    }
  }

  public static class AlphabetLine {
    public String line;
    public String templateName;
    public String featureName;
    public int template;
    public int feature;
    public AlphabetLine(String line) {
      set(line);
    }
    @Override
    public String toString() {
      return "(AlphabetLine " + line + ")";
    }
    public boolean isNull() {
      return line == null;
    }
    public void set(String line) {
      this.line = line;
      if (line != null) {
        String[] toks = line.split("\t");
        assert toks.length == 4;
        template = Integer.parseInt(toks[0]);
        feature = Integer.parseInt(toks[1]);
        templateName = toks[2];
        featureName = toks[3];
      }
    }
    public static Comparator<AlphabetLine> BY_TEMPLATE_STR_FEATURE_STR = new Comparator<AlphabetLine>() {
      @Override
      public int compare(AlphabetLine o1, AlphabetLine o2) {
        int c1 = o1.templateName.compareTo(o2.templateName);
        if (c1 != 0)
          return c1;
        int c2 = o1.featureName.compareTo(o2.featureName);
        return c2;
      }
    };
  }

  // Features
  protected Alphabet templates;

  protected IntObjectBimap<String> kNames;    // all strings output for k
  protected int frUkn, fUkn, rUkn;

  // If false, then frame mode. the "k" slot is used for the frame id, the
  // arg span slot is set to copy the target, and all of the features are based
  // on just the target.
  protected final boolean roleMode;

  public FeaturePrecomputation(boolean roleMode) {
    this(roleMode, new Alphabet()); // use all templates
  }

  /**
   * You need to provide a {@link FrameIndex} so that a stable bijection from
   * role/frameRole names to ints can be made. This needs to be stable across
   * shards since k names are not merged.
   */
  public FeaturePrecomputation(boolean roleMode, Alphabet templates) {
    this.templates = templates;
    this.roleMode = roleMode;
    kNames = new IntObjectBimap<>();
    rUkn = kNames.lookupIndex("r=UKN", true);
    frUkn = kNames.lookupIndex("fr=UKN", true);
    fUkn = kNames.lookupIndex("f=UKN", true);
    for (FrameIndex fi : Arrays.asList(FrameIndex.getFrameNet(), FrameIndex.getPropbank())) {
      for (Frame f : fi.allFrames()) {
        kNames.lookupIndex("f=" + f.getName(), true);
        if (roleMode) {
          for (int k = 0; k < f.numRoles(); k++) {
            kNames.lookupIndex("r=" + f.getRole(k), true);
            kNames.lookupIndex("fr=" + f.getFrameRole(k), true);
          }
        }
      }
    }
    Log.info("kNames.size=" + kNames.size());
    Log.info("roleMode=" + roleMode);
  }

  /**
   * Compute all of the features and dump them to a file.
   */
  public void run(Iterator<FNParse> data) {
    Log.info("extracting features for " + (roleMode ? "role" : "frame") + " id");
 
    // For debugging
    ExperimentProperties config = ExperimentProperties.getInstance();
    int max = config.getInt("limit", 0);
    Log.info("limit=" + max);

    // Scan the data
    Counts<String> parseStats = new Counts<>();
    TimeMarker tm = new TimeMarker();
    while (data.hasNext() && (max <= 0 || tm.numMarks() < max)) {
      FNParse y = data.next();

      if (roleMode)
        emitAllRoleId(y);
      else
        emitAllFrameId(y);

      if (tm.enoughTimePassed(15)) {
        Log.info("processed " + tm.numMarks()
        + " sentences in " + tm.secondsSinceFirstMark() + " seconds");
      }

      // Tally up some stats for debugging
      parseStats.increment("num-parses");
      if (y.getSentence().getBasicDeps(false) == null)
        parseStats.increment("no-basic-deps");
      if (y.getSentence().getCollapsedDeps(false) == null)
        parseStats.increment("no-collapsed-deps");
      if (y.getSentence().getStanfordParse(false) == null)
        parseStats.increment("no-stanford-deps");
    }
    Log.info("done computing features");
    System.out.println("parseStats=" + parseStats);

    Log.info("calling onComplete");
    onComplete();

    Log.info("done");
  }

  /**
   * Extracts features for every width=1 target in the sentence.
   * If a target is wider than width=1, it will not be included in output.
   *
   * NOTE: This really shouldn't be public, don't use if you don't have to.
   */
  public void emitAllFrameId(FNParse y) {
    assert !roleMode;

    // I'm going to use all width=1 spans as possible targets
    Map<Span, FrameInstance> frameLocations = y.getFrameLocations();
    HeadFinder hf = templates.getHeadFinder();
    Sentence s = y.getSentence();
    TemplateContext ctx = new TemplateContext();
    int n = s.size();
    for (int i = 0; i < n; i++) {
      Span t = Span.getSpan(i, i+1);

      String docId = "na";  // Not currently needed
      Target ta = new Target(docId, y.getId(), t);

      // Extract features
      List<Feature> features = new ArrayList<>();
      for (TemplateAlphabet tmpl : templates) {
        FeatureIGComputation.setFrameIdContext(y, t, ctx, hf);
        Iterable<String> feats = tmpl.template.extract(ctx);
        if (feats != null) {
          for (String feat : feats) {
            int featIdx = tmpl.alph.lookupIndex(feat, true);
            features.add(new Feature(tmpl.name, tmpl.index, feat, featIdx, 1.0));
          }
        }
      }

      FrameInstance fi = frameLocations.get(t);
//      int k = fi == null ? fUkn : kNames.lookupIndex("f=" + fi.getFrame().getName(), true);
      int k = fi == null ? fUkn : lookupFrame(fi.getFrame());
      String ks = "-1,-1," + k;
      emit(ta, Span.nullSpan, ks, features);
    }
  }


  public void emitAllRoleId(FNParse y) {
    // This is how we prune spans
    Reranker r = new Reranker(null, null, null, Mode.XUE_PALMER_HERMANN, null, 1, 1, new Random(9001));
    emitAllRoleId(y, r);
  }

  public int lookupFrame(String f) {
    return kNames.lookupIndex("f=" + f, false);
  }
  public int lookupFrame(Frame f) {
    return kNames.lookupIndex("f=" + f.getName(), false);
  }
  public int lookupFrameRole(Frame f, int k) {
    return kNames.lookupIndex("fr=" + f.getFrameRole(k), false);
  }
  public int lookupFrameRole(String f, String k) {
    return kNames.lookupIndex("fr=" + f + "/" + k, false);
  }
  public int lookupRole(String k) {
    return kNames.lookupIndex("r=" + k, false);
  }
  public static class AlphWrapper extends FeaturePrecomputation {
    public AlphWrapper(boolean roleMode, Alphabet feats) {
      super(roleMode, feats);
    }
    @Override
    public void emit(Target t, Span s, String k, List<Feature> features) {
      throw new RuntimeException("only use this class for the Alphabet!");
    }
    @Override
    public void onComplete() {} // no-op
    public void saveAlphabets(File templateFeatureAlphabet, File outputRoleNames) {
      // Save the alphabet
      // template -> feature -> index
      Log.info("saving template-feature alphabet to " + templateFeatureAlphabet.getPath());
      try {
        templates.toFile(templateFeatureAlphabet);
      } catch (IOException e) {
        e.printStackTrace();
      }

      Log.info("saving the role names to " + outputRoleNames.getPath());
      try (BufferedWriter w = FileUtil.getWriter(outputRoleNames)) {
        w.write("-1\tnoRole\n");
        for (int i = 0; i < kNames.size(); i++)
          w.write(i + "\t" + kNames.lookupObject(i) + "\n");
      } catch (IOException e) {
        e.printStackTrace();
      }
      Log.info("done");
    }
  }

  /**
   * Will emit k values for just the role (ignoring the frame, i.e. proper for PB)
   * and for the frame and role (via {@link FrameRolePacking}).
   *
   * NOTE: This really shouldn't be public, don't use if you don't have to.
   */
  public void emitAllRoleId(FNParse y, Reranker r) {
    assert roleMode;

    // Keep track of what (t,s) I have already emitted and not emit duplicates
    Set<IntTrip> emittedTS = new HashSet<>();

    // Use the COMMIT actions to loop over (t,k,s)
    State st = r.getInitialStateWithPruning(y, y);
    TemplateContext ctx = new TemplateContext();

    // Loop over all COMMIT actions, and thus (t,k,s)
    for (Action commit : ActionType.COMMIT.next(st)) {
      Span s = commit.getSpan();
      if (!emittedTS.add(new IntTrip(commit.t, s.start, s.end)))
        continue;

      // Find if this (t,s) corresponds to a role
      Span ta = y.getFrameInstance(commit.t).getTarget();
      StringBuilder k = null;
      if (s != Span.nullSpan) {
        FrameInstance fi = y.getFrameInstance(commit.t);
        Frame f = fi.getFrame();
        int K = fi.getFrame().numRoles();
        for (int ki = 0; ki < K; ki++) {
          Span arg = fi.getArgument(ki);
          if (arg == s) {

            String fs = f.getName();
            String rs = f.getRole(ki);
            String frs = f.getFrameRole(ki);

//            int role = kNames.lookupIndex("r=" + rs, false);
//            int frameRole = kNames.lookupIndex("fr=" + frs, false);
//            int frame = kNames.lookupIndex("f=" + fs, false);
            int role = lookupRole(rs);
            int frameRole = lookupFrameRole(f, ki);
            int frame = lookupFrame(f);

            if (role < 0) {
              Log.warn("unknown r: " + rs);
              role = rUkn;
            }
            if (frameRole < 0) {
              Log.warn("unknown fr: " + frs);
              frameRole = frUkn;
            }
            if (frame < 0) {
              Log.warn("unknown f: " + fs);
              frame = fUkn;
            }

            if (k == null)
              k = new StringBuilder(role + "," + frameRole + "," + frame);
            else
              k.append("," + role + "," + frameRole + "," + frame);
          }
        }
      }
      if (k == null) {
        // TODO Change to "-1,-1,-1"?
        k = new StringBuilder("-1");
      }

      String docId = "na";  // Not currently needed
      Target t = new Target(docId, y.getId(), ta);

      // Extract features
      List<Feature> features = new ArrayList<>();
      for (TemplateAlphabet tmpl : templates) {
        FeatureIGComputation.setRoleIdContext(y, commit, ctx, templates.getHeadFinder());
        Iterable<String> feats = tmpl.template.extract(ctx);
        if (feats != null) {
          for (String feat : feats) {
            int featIdx = tmpl.alph.lookupIndex(feat, true);
            features.add(new Feature(tmpl.name, tmpl.index, feat, featIdx, 1.0));
          }
        }
      }

      emit(t, s, k.toString(), features);
    }
  }

  /** Emits one line */
  public abstract void emit(Target t, Span s, String k, List<Feature> features);

  /** For writing out other things like role names. */
  public abstract void onComplete();

  /**
   * Sub-class which writes extracted features and ancillary data to disk.
   */
  public static class ToDisk extends FeaturePrecomputation {
    private Writer outputDataWriter;    // writer for outputData
    private File outputAlphabet;
    private File outputRoleNames;

    public ToDisk(boolean roleMode, Alphabet templates,
        File outputData, File outputAlphabet, File outputRoleNames)
            throws IOException {
      super(roleMode, templates);
      Log.info("writing features to " + outputData.getPath());
      Log.info("writing alphabet to " + outputAlphabet.getPath());
      Log.info("writing role names to " + outputRoleNames.getPath());
      this.outputAlphabet = outputAlphabet;
      this.outputRoleNames = outputRoleNames;
      this.outputDataWriter = FileUtil.getWriter(outputData);
    }

    @Override
    public void emit(Target t, Span s, String k, List<Feature> features) {
      try {
        outputDataWriter.write(Target.toLine(t));
        outputDataWriter.write("\t" + s.shortString());
        outputDataWriter.write("\t" + k);
        for (Feature f : features)
          outputDataWriter.write("\t" + f.template + ":" + f.feature);
        outputDataWriter.write('\n');
      } catch (IOException e) {
        throw new RuntimeException (e);
      }
    }

    @Override
    public void onComplete() {
      try {
        this.outputDataWriter.close();
      } catch (IOException e) {
        e.printStackTrace();
      }

      // Save the alphabet
      // template -> feature -> index
      Log.info("saving alphabet");
      try {
        templates.toFile(outputAlphabet);
      } catch (IOException e) {
        e.printStackTrace();
      }

      Log.info("saving the role names");
      try (BufferedWriter w = FileUtil.getWriter(outputRoleNames)) {
        w.write("-1\tnoRole\n");
        for (int i = 0; i < kNames.size(); i++)
          w.write(i + "\t" + kNames.lookupObject(i) + "\n");
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

  public static class ToMemBuffer extends FeaturePrecomputation {

    private Map<Span, List<Feature>> targetFeats;   // roleMode=false
    private Map<SpanPair, List<Feature>> argFeats;  // roleMode=true

    public ToMemBuffer(boolean roleMode, Alphabet templates) {
      super(roleMode, templates);
      if (roleMode)
        this.argFeats = new HashMap<>();
      else
        this.targetFeats = new HashMap<>();
    }

    @Override
    public void emit(Target ta, Span s, String k, List<Feature> features) {
      if (targetFeats != null) {
        Span key = ta.target;
        Object oldF = targetFeats.put(key, features);
        assert oldF == null;
      }
      if (argFeats != null) {
        SpanPair key = new SpanPair(ta.target, s);
        Object oldF = argFeats.put(key, features);
        assert oldF == null;
      }
    }

    @Override
    public void onComplete() {
      if (targetFeats != null)
        targetFeats.clear();
      if (argFeats != null)
        argFeats.clear();
    }

    public List<Feature> getTargetFeatures(Span target) {
      return targetFeats.get(target);
    }

    public Iterable<Entry<SpanPair, List<Feature>>> getArgFeatures() {
      return argFeats.entrySet();
    }

    public List<Feature> getArgFeaturesFor(Span t, Span s) {
      SpanPair key = new SpanPair(t, s);
      return argFeats.get(key);
    }
  }

  /**
   * Returns the roles that appear for this instance (t,s). Will be array of
   * length 1 for PB, could be longer for FN.
   *
   * NOTE: This does not return the int[] seen in the text file, but only the
   * %3=0 indices of it (the roles).
   */
  public static int[] getRoles(String line) {
    return getRolesHelper(line, ROLE_STRING_COLUMN_ROLE_MOD3);
  }
  /**
   * Returns the frame-roles that appear for this instance (t,s). Will be array
   * of length 1 for PB, could be longer for FN.
   *
   * NOTE: This does not return the int[] seen in the text file, but only the
   * %3=1 indices of it (the frame-roles).
   */
  public static int[] getFrameRoles(String line) {
    return getRolesHelper(line, ROLE_STRING_COLUMN_FRAMEROLE_MOD3);
  }
  /**
   * Returns the frames that appear for this instance (t,s). Will be array of
   * length 1 for PB, could be longer for FN.
   *
   * NOTE: This does not return the int[] seen in the text file, but only the
   * %3=2 indices of it (the frames).
   */
  public static int[] getFrames(String line) {
    return getRolesHelper(line, ROLE_STRING_COLUMN_FRAME_MOD3);
  }
  private static int[] getRolesHelper(String line, int mod3) {
    if (mod3 >= 3 || mod3 < 0)
      throw new IllegalArgumentException();
    String[] toks = line.split("\t", ROLE_STRING_COLUMN + 2);
    return getRolesHelperTok(toks[ROLE_STRING_COLUMN], mod3);
  }
  static int[] getRolesHelperTok(String kString, int mod3) {
    if (kString.equals("-1"))
      return new int[] {-1};
    String[] rolesS = kString.split(",");
    if (rolesS.length % 3 != 0)
      throw new IllegalArgumentException("malformed roles string: " + kString);
    int[] rolesI = new int[rolesS.length / 3];
    for (int i = 0; i < rolesS.length; i += 3)
      rolesI[i] = Integer.parseInt(rolesS[i + mod3]);
    return rolesI;
  }

  public static Iterable<FNParse> getData(String dataset, boolean addParses) {
    ExperimentProperties config = ExperimentProperties.getInstance();
    Iterable<FNParse> data = Collections.emptyList();
    Shard shard = ShardUtils.getShard(config);
    Log.info("shard=" + shard);

    // You can specify Communication tar.gz files using the "concrete:" prefix
    String concretePrefix = "concrete:";
    if (dataset.startsWith(concretePrefix)) {
      List<FNParse> parses = new ArrayList<>(); // eagerly reads in Communications/FNParses
      String[] files = dataset.substring(concretePrefix.length()).split(",");
      BrownClusters bc256 = null;
      BrownClusters bc1000 = null;
      IRAMDictionary wordNet = null;
      Language lang = Language.EN;
      ConcreteToDocument c2d = new ConcreteToDocument(bc256, bc1000, wordNet, lang);
      c2d.readConcreteStanford();
      boolean addGoldParse = false;
      boolean addStanfordParse = true;
      boolean addStanfordBasicDParse = true;
      boolean addStanfordCollapsedDParse = false;   // dep graphs with >1 parent break
      boolean takeGoldPos = false;
      c2d.debug = true;
//      c2d.debug_cons = true;
//      c2d.debug_propbank = true;
      MultiAlphabet alph = new MultiAlphabet();
      int docIndex = 0;
      for (String f : files) {
        Log.info("reading Communications from " + f);
        try (InputStream is = new FileInputStream(new File(f))) {
          Iterator<Communication> iter =
              new TarGzArchiveEntryCommunicationIterator(is);
          iter = ShardUtils.shardByIndex(iter, shard);
          while (iter.hasNext()) {
            Communication c = iter.next();
            ConcreteDocumentMapping cdm = c2d.communication2Document(c, docIndex++, alph, lang);
            Document d = cdm.getDocument();
            parses.addAll(DataUtil.convert(d,
                addGoldParse, addStanfordParse, addStanfordBasicDParse,
                addStanfordCollapsedDParse, takeGoldPos));
          }
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
      data = Iterables.concat(data, parses);
    }

    // Poorly named: provides parses via redis for both propbank/framenet
    ParsePropbankData.Redis propbankAutoParses = null;
    if (addParses)
      propbankAutoParses = new ParsePropbankData.Redis(config);

    if ("propbank".equalsIgnoreCase(dataset) || "both".equalsIgnoreCase(dataset)) {
      Log.info("reading propbank");
      PropbankReader pbr = new PropbankReader(config, propbankAutoParses);
      pbr.setKeep(s -> Math.floorMod(s.getId().hashCode(), shard.second) == shard.first);
      data = new Iterable<FNParse>() {
        @Override
        public Iterator<FNParse> iterator() {
          Iterator<FNParse> i = Collections.emptyIterator();
          i = Iterators.concat(i, pbr.getTrainDataStream().iterator());
          i = Iterators.concat(i, pbr.getDevDataStream().iterator());
          i = Iterators.concat(i, pbr.getTestDataStream().iterator());
          return i;
        }
      };
    }

    if ("framenet".equalsIgnoreCase(dataset) || "both".equalsIgnoreCase(dataset)) {
      Log.info("reading framenet");
      Iterable<FNParse> train = () -> FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences();
      Iterable<FNParse> test = () -> FileFrameInstanceProvider.dipanjantestFIP.getParsedSentences();
      Iterable<FNParse> unparsed = ShardUtils.shard(Iterables.concat(train, test), p -> p.getSentence().getId().hashCode(), shard);

      // Just load it into memory and parse
      List<FNParse> parsed = new ArrayList<>();
      for (FNParse y : unparsed) {
        if (addParses) {
          Sentence s = y.getSentence();
          if (s.getStanfordParse(false) == null) {
            ConstituencyParse cp = propbankAutoParses.parse(s);
            s.setStanfordParse(cp);
          }
          if (s.getBasicDeps(false) == null) {
            DependencyParse dp = propbankAutoParses.getBasicDeps(s);
            s.setBasicDeps(dp);
          }
        }
        parsed.add(y);
      }
      data = Iterables.concat(data, parsed);
    }
    return data;
  }

  public static void main(String[] args) throws IOException {
    ExperimentProperties config = ExperimentProperties.init(args);
    File wd = config.getExistingDir("workingDir", new File("/tmp"));

    String dataset = config.getString("dataset");
    boolean addParses = config.getBoolean("addParses", true);
    Iterable<FNParse> data = getData(dataset, addParses);

    // Allows you to change compression, ["", ".gz", ".bz2"]
    String suffix = config.getString("suffix", ".gz");

    // True means extract features for role id.
    // False means extract features for frame id.
    boolean roleMode = config.getBoolean("roleMode");

    Alphabet templates = new Alphabet();  // read all templates
    FeaturePrecomputation fp = new FeaturePrecomputation.ToDisk(
        roleMode,
        templates,
        new File(wd, "features.txt" + suffix),
        new File(wd, "template-feat-indices.txt" + suffix),
        new File(wd, "role-names.txt" + suffix));
    fp.run(data.iterator());
  }
}
