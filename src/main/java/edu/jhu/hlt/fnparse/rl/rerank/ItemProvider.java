package edu.jhu.hlt.fnparse.rl.rerank;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.WeightedFrameInstance;
import edu.jhu.hlt.fnparse.datatypes.WeightedFrameInstance.ArgTheory;
import edu.jhu.hlt.fnparse.experiment.grid.FinalResults;
import edu.jhu.hlt.fnparse.inference.role.span.LatentConstituencyPipelinedParser;
import edu.jhu.hlt.fnparse.inference.role.span.RoleSpanLabelingStage;
import edu.jhu.hlt.fnparse.util.HasId;

public interface ItemProvider {
  public int size();
  public FNParse label(int i);
  public List<Item> items(int i);

  /**
   * returns and adds to addTo all of the labels in ip
   */
  public static <T extends Collection<FNParse>> T allLabels(ItemProvider ip, T addTo) {
    int n = ip.size();
    for (int i = 0; i < n; i++)
      addTo.add(ip.label(i));
    return addTo;
  }

  public static List<FNParse> allLabels(ItemProvider ip) {
    List<FNParse> l = new ArrayList<>();
    allLabels(ip, l);
    return l;
  }

  public static class Caching implements ItemProvider {
    private FNParse[] labels;
    private List<Item>[] items;
    private ItemProvider base;
    @SuppressWarnings("unchecked")
    public Caching(ItemProvider wrap) {
      int n = wrap.size();
      base = wrap;
      labels = new FNParse[n];
      items = new List[n];
      for (int i = 0; i < n; i++) {
        labels[i] = wrap.label(i);
        items[i] = wrap.items(i);
      }
    }
    public ItemProvider getBase() {
      return base;
    }
    @SuppressWarnings("unchecked")
    public Caching(File f, FrameInstanceProvider superset) {
      try {
        InputStream is = new FileInputStream(f);
        if (f.getName().toLowerCase().endsWith(".gz"))
          is = new GZIPInputStream(is);
        DataInputStream dis = new DataInputStream(is);

        // items
        int n = dis.readInt();
        items = new List[n];
        for (int i = 0; i < n; i++) {
          int m = dis.readInt();
          items[i] = new ArrayList<Item>(m);
          for (int j = 0; j < m; j++) {
            Item itj = new Item(-1, -2, null, Double.NaN);
            itj.deserialize(dis);
            items[i].add(itj);
          }
        }

        // labels
        List<FNParse> ss = DataUtil.iter2list(superset.getParsedSentences());
        List<FNParse> labelsL = HasId.readItemsById(ss, dis, n);
        assert labelsL.size() == n;
        labels = new FNParse[n];
        for (int i = 0; i < n; i++) labels[i] = labelsL.get(i);

        dis.close();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
    public void save(File f) throws IOException {
      OutputStream os = new FileOutputStream(f);
      if (f.getName().toLowerCase().endsWith(".gz"))
        os = new GZIPOutputStream(os);
      DataOutputStream dos = new DataOutputStream(os);

      // items
      dos.writeInt(items.length);     // how many items/labels
      for (List<Item> li : items) {
        dos.writeInt(li.size());      // how many items for this label
        for (Item i : li)
          i.serialize(dos);           // each item
      }

      // labels
      HasId.writeItemsById(Arrays.asList(labels), dos);

      dos.close();
    }
    @Override
    public int size() {
      assert labels.length == items.length;
      return labels.length;
    }
    @Override
    public FNParse label(int i) {
      return labels[i];
    }
    @Override
    public List<Item> items(int i) {
      return items[i];
    }
  }

  public static class Slow implements ItemProvider {
    private FNParse[] labels;
    private List<Item>[] items;
    private LatentConstituencyPipelinedParser parser;
    private File modelDir = new File("saved-models/good-latent-span/");
    @SuppressWarnings("unchecked")
    public Slow(List<FNParse> parses) {
      if (!modelDir.isDirectory())
        throw new RuntimeException("model dir doesn't exist: " + modelDir.getPath());

      FinalResults hasParser = new FinalResults(modelDir, new Random(9001), "span", 0, false);
      hasParser.loadModel();
      parser = (LatentConstituencyPipelinedParser) hasParser.getParser();
      hasParser = null;
      RoleSpanLabelingStage rsls = (RoleSpanLabelingStage) parser.getRoleLabelingStage();
      rsls.maxSpansPerArg = 10;

      // will return FNParses with WeightedFrameInstances
      List<FNParse> hyp = parser.parse(DataUtil.stripAnnotations(parses), parses);
      assert hyp.size() == parses.size();

      int n = parses.size();
      labels = new FNParse[n];
      items = new List[n];
      for (int i = 0; i < n; i++) {
        labels[i] = parses.get(i);
        items[i] = new ArrayList<>();
        FNParse h = hyp.get(i);
        int T = h.numFrameInstances();
        for (int t = 0; t < T; t++) {
          WeightedFrameInstance wfi = (WeightedFrameInstance) h.getFrameInstance(t);
          int K = wfi.getFrame().numRoles();
          for (int k = 0; k < K; k++)
            for (ArgTheory atk : wfi.getArgumentTheories(k))
              items[i].add(new Item(t, k, atk.span, atk.weight));
        }
      }
    }
    @Override
    public int size() {
      assert labels.length == items.length;
      return labels.length;
    }
    @Override
    public FNParse label(int i) {
      return labels[i];
    }
    @Override
    public List<Item> items(int i) {
      return items[i];
    }
  }

}