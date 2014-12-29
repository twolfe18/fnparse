package edu.jhu.hlt.fnparse.rl.rerank;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.WeightedFrameInstance;
import edu.jhu.hlt.fnparse.datatypes.WeightedFrameInstance.ArgTheory;
import edu.jhu.hlt.fnparse.inference.role.span.LatentConstituencyPipelinedParser;
import edu.jhu.hlt.fnparse.inference.role.span.RoleSpanLabelingStage;

public interface ItemProvider {
  public int size();
  public FNParse label(int i);
  public List<Item> items(int i);

  public static class Caching implements ItemProvider {
    private FNParse[] labels;
    private List<Item>[] items;
    private ItemProvider base;
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
    public Caching(File f) {
      try {
        InputStream is = new FileInputStream(f);
        if (f.getName().toLowerCase().endsWith(".gz"))
          is = new GZIPInputStream(is);
        ObjectInputStream ois = new ObjectInputStream(is);
        labels = (FNParse[]) ois.readObject();
        items = (List<Item>[]) ois.readObject();
        ois.close();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
    public void save(File f) throws IOException {
      OutputStream os = new FileOutputStream(f);
      if (f.getName().toLowerCase().endsWith(".gz"))
        os = new GZIPOutputStream(os);
      ObjectOutputStream oos = new ObjectOutputStream(os);
      oos.writeObject(labels);
      oos.writeObject(items);
      oos.close();
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
    private File modelDir = new File("saved-models/good-latent-span");
    public Slow(List<FNParse> parses) {
      if (!modelDir.isDirectory())
        throw new RuntimeException("model dir doesn't exist: " + modelDir.getPath());

      parser = new LatentConstituencyPipelinedParser();
      parser.loadModel(modelDir);
      RoleSpanLabelingStage rsls = (RoleSpanLabelingStage) parser.getRoleLabelingStage();
      rsls.maxSpansPerArg = 10;

      // will return FNParses with WeightedFrameInstances
      List<FNParse> hyp = parser.parse(DataUtil.stripAnnotations(parses), null);
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