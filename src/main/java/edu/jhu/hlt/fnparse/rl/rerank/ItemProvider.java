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

import edu.jhu.hlt.fnparse.datatypes.FNParse;

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
    public Slow(List<FNParse> parses) {
      int n = parses.size();
      labels = new FNParse[n];
      items = new List[n];
      for (int i = 0; i < n; i++) {
        labels[i] = parses.get(i);
        items[i] = new ArrayList<>();
        // TODO call parser
        throw new RuntimeException("implement me");
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