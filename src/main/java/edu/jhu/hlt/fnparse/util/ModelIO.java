package edu.jhu.hlt.fnparse.util;

import java.io.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.apache.log4j.Logger;

import edu.jhu.gm.model.FgModel;
import edu.jhu.prim.util.Lambda.FnIntDoubleToDouble;
import edu.jhu.util.Alphabet;

public class ModelIO {

  public static Logger LOG = Logger.getLogger(ModelIO.class);

  public static boolean preventOverwrites = false;

  public static void writeFeatureNameWeightsBinary(
      double[] weights,
      Alphabet<String> featureNames,
      DataOutputStream dos) throws IOException {
    int nonzero = 0;
    for (int i = 0; i < weights.length; i++)
      if (weights[i] != 0d)
        nonzero++;
    dos.writeInt(nonzero);
    final int n = Math.min(weights.length, featureNames.size());
    for (int i = 0; i < n; i++) {
      if (weights[i] == 0d)
        continue;
      String fn = featureNames.lookupObject(i);
      dos.writeUTF(fn);
      dos.writeDouble(weights[i]);
    }
  }

  public static double[] readFeatureNameWeightsBinary(
      DataInputStream dis,
      Alphabet<String> featureNames)
      throws IOException {
    int nonzero = dis.readInt();
    int[] idx = new int[nonzero];
    double[] weights = new double[nonzero];
    int maxIdx = 0;
    for (int i = 0; i < nonzero; i++) {
      String fn = dis.readUTF();
      weights[i] = dis.readDouble();
      idx[i] = featureNames.lookupIndex(fn, true);
      if (idx[i] > maxIdx)
        maxIdx = idx[i];
    }
    double[] ps = new double[maxIdx + 1];
    for (int i = 0; i < nonzero; i++) {
      if (idx[i] < 0)
        continue;
      if (ps[idx[i]] != 0d)
        throw new RuntimeException();
      ps[idx[i]] = weights[i];
    }
    return ps;
  }

  public static void writeHumanReadable(
      FgModel model,
      Alphabet<String> featIdx,
      File f,
      boolean outputZeroFeatures) {
    LOG.info("writing human readable model to " + f.getPath());
    if(preventOverwrites && f.isFile())
      throw new IllegalArgumentException(f.getPath() + " is already a file");
    if(model == null || featIdx == null)
      throw new IllegalArgumentException();
    try {
      double[] values = new double[model.getNumParams()];
      model.updateDoublesFromModel(values);
      OutputStream os = new FileOutputStream(f);
      if (f.getName().toLowerCase().endsWith(".gz"))
        os = new GZIPOutputStream(os);
      BufferedWriter w = new BufferedWriter(new OutputStreamWriter(os));
      int n = Math.min(model.getNumParams(), featIdx.size());
      for (int i = 0; i < n; i++) {
        if (!outputZeroFeatures && Math.abs(values[i]) < 1e-5)
          continue;
        String fName = featIdx.lookupObject(i);
        w.write(String.format("%f\t%s\n", values[i], fName));
      }
      w.close();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static void writeBinary(FgModel model, File f) {
    LOG.info("writing model to " + f.getPath());
    if(preventOverwrites && f.isFile())
      throw new IllegalArgumentException(f.getPath() + " is already a file");
    try {
      DataOutputStream dos = new DataOutputStream(new FileOutputStream(f));
      writeBinary(model, dos);
      dos.close();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /** writes a dense vector in binary */
  public static void writeBinary(FgModel model, DataOutputStream dos)
      throws IOException {
    int n = model.getNumParams();
    double[] values = new double[n];
    model.updateDoublesFromModel(values);
    dos.writeInt(n);
    for (int i = 0; i < n; i++)
      dos.writeDouble(values[i]);
  }

  public static void writeBinaryWithStringFeatureNames(FgModel model, Alphabet<String> featureNames, DataOutputStream dos) {
    int n = model.getNumParams();
    try {
      dos.writeInt(n);
      model.apply(new FnIntDoubleToDouble() {
        @Override
        public double call(int arg0, double arg1) {
          String fn = featureNames.lookupObject(arg0);
          try {
            dos.writeUTF(fn);
            dos.writeDouble(arg1);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
          return arg1;
        }
      });
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static FgModel readBinaryWithStringFeatureNames(Alphabet<String> featureNames, DataInputStream dis) {
    if (!featureNames.isGrowing())
      throw new RuntimeException();
    try {
      int n = dis.readInt();
      assert n > 0;
      int[] indices = new int[n];
      double[] weights = new double[n];
      for (int i = 0; i < n; i++) {
        String fn = dis.readUTF();
        weights[i] = dis.readDouble();
        indices[i] = featureNames.lookupIndex(fn, true);
      }
      FgModel model = new FgModel(featureNames.size());
      for (int i = 0; i < n; i++)
        model.add(indices[i], weights[i]);
      return model;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static FgModel readBinary(File f) {
    LOG.info("reading binary model from " + f.getPath());
    if(!f.isFile())
      throw new IllegalArgumentException(f.getPath() + " is not a file");
    try {
      DataInputStream dis = new DataInputStream(new FileInputStream(f));
      FgModel model = readBinary(dis);
      dis.close();
      return model;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static FgModel readBinary(DataInputStream dis) throws IOException {
    int dimension = dis.readInt();
    LOG.debug("[readBinary] dimension=" + dimension);
    FgModel model = new FgModel(dimension);
    double[] values = new double[dimension];
    for(int i=0; i<dimension; i++)
      values[i] = dis.readDouble();
    model.updateModelFromDoubles(values);
    return model;
  }

  /**
   * @return an alphabet from a file which is guaranteed to not be growing.
   */
  public static Alphabet<String> readAlphabet(File f) {
    LOG.info("reading alphabet from " + f.getPath());
    if(!f.isFile())
      throw new IllegalArgumentException(f.getPath() + " is not a file");
    try {
      Timer t = Timer.start("");
      Alphabet<String> alph = new Alphabet<String>();
      InputStream is = new FileInputStream(f);
      if(f.getName().toLowerCase().endsWith(".gz"))
        is = new GZIPInputStream(is);
      BufferedReader r = new BufferedReader(new InputStreamReader(is));
      while(r.ready()) {
        String e = r.readLine();
        alph.lookupIndex(e, true);
      }
      r.close();
      alph.stopGrowth();
      t.stop();
      LOG.info(String.format("read %d entries from %s in %.1f seconds",
          alph.size(), f.getPath(), t.totalTimeInSeconds()));
      return alph;
    } catch (Exception e1) {
      throw new RuntimeException(e1);
    }
  }

  public static void writeAlphabet(Alphabet<String> alph, File f) {
    if(preventOverwrites && f.isFile())
      throw new IllegalArgumentException(f.getPath() + " is already a file");
    try {
      Timer t = Timer.start("");
      OutputStream os = new FileOutputStream(f);
      if(f.getName().toLowerCase().endsWith(".gz"))
        os = new GZIPOutputStream(os);
      try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(os))) {
        int n = alph.size();
        for (int i = 0; i < n; i++) {
          String e = alph.lookupObject(i);
          if (e.contains("\n"))
            throw new RuntimeException("this feature name contains my delimiter (newline): " + e);
          w.write(e);
          w.write("\n");
        }
      }
      t.stop();
      LOG.info(String.format(
          "[ModelIO.writeAlphabet] wrote %d entries to %s in %.1f seconds",
          alph.size(), f.getPath(), t.totalTimeInSeconds()));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
