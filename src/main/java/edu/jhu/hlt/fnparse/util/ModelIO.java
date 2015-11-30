package edu.jhu.hlt.fnparse.util;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.zip.GZIPOutputStream;

import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Timer;
import edu.jhu.util.Alphabet;

public class ModelIO {

  // Came from pacaya, removing that dependency
  public static interface FgModel {
    public int getNumParams();
    public void updateDoublesFromModel(double[] values);
  }

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
    Log.info("writing human readable model to " + f.getPath());
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
    Log.info("writing model to " + f.getPath());
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
//    int n = model.getNumParams();
//    try {
//      dos.writeInt(n);
//      model.apply(new FnIntDoubleToDouble() {
//        @Override
//        public double call(int arg0, double arg1) {
//          String fn = featureNames.lookupObject(arg0);
//          try {
//            dos.writeUTF(fn);
//            dos.writeDouble(arg1);
//          } catch (Exception e) {
//            throw new RuntimeException(e);
//          }
//          return arg1;
//        }
//      });
//    } catch (Exception e) {
//      throw new RuntimeException(e);
//    }
    throw new RuntimeException("re-implement me");
  }

  public static FgModel readBinaryWithStringFeatureNames(Alphabet<String> featureNames, DataInputStream dis) {
    if (!featureNames.isGrowing())
      throw new RuntimeException();
//    try {
//      int n = dis.readInt();
//      assert n > 0;
//      int[] indices = new int[n];
//      double[] weights = new double[n];
//      for (int i = 0; i < n; i++) {
//        String fn = dis.readUTF();
//        weights[i] = dis.readDouble();
//        indices[i] = featureNames.lookupIndex(fn, true);
//      }
//      FgModel model = new FgModel(featureNames.size());
//      for (int i = 0; i < n; i++)
//        model.add(indices[i], weights[i]);
//      return model;
//    } catch (Exception e) {
//      throw new RuntimeException(e);
//    }
    throw new RuntimeException("re-implement me");
  }

  public static FgModel readBinary(File f) {
    Log.info("reading binary model from " + f.getPath());
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
//    int dimension = dis.readInt();
//    Log.debug("[readBinary] dimension=" + dimension);
//    FgModel model = new FgModel(dimension);
//    double[] values = new double[dimension];
//    for(int i=0; i<dimension; i++)
//      values[i] = dis.readDouble();
//    model.updateModelFromDoubles(values);
//    return model;
    throw new RuntimeException("re-implement me");
  }

  /**
   * @return an alphabet from a file which is guaranteed to not be growing.
   */
  public static Alphabet<String> readAlphabet(File f) {
    Log.info("reading alphabet from " + f.getPath());
    if(!f.isFile())
      throw new IllegalArgumentException(f.getPath() + " is not a file");
    Timer t = Timer.start("");
    Alphabet<String> alph = new Alphabet<String>();
    try (BufferedReader r = FileUtil.getReader(f)) {
      for (String line = r.readLine(); line != null; line = r.readLine())
        alph.lookupIndex(line, true);
    } catch (Exception e1) {
      throw new RuntimeException(e1);
    }
    alph.stopGrowth();
    t.stop();
    Log.info(String.format("read %d entries from %s in %.1f seconds",
        alph.size(), f.getPath(), t.totalTimeInSeconds()));
    return alph;
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
      Log.info(String.format(
          "[ModelIO.writeAlphabet] wrote %d entries to %s in %.1f seconds",
          alph.size(), f.getPath(), t.totalTimeInSeconds()));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /** Only works for hyper-cubic tensors */
  public static double[][][] readTensor3(DataInputStream dis) throws IOException {
    int x = dis.readInt();
    int y = dis.readInt();
    int z = dis.readInt();
    double[][][] t3 = new double[x][y][z];
    for (int i = 0; i < x; i++)
      for (int j = 0; j < y; j++)
        for (int k = 0; k < z; k++)
          t3[i][j][k] = dis.readDouble();
    return t3;
  }

  /** Only works for hyper-cubic tensors */
  public static double[][] readTensor2(DataInputStream dis) throws IOException {
    int x = dis.readInt();
    int y = dis.readInt();
    double[][] t2 = new double[x][y];
    for (int i = 0; i < x; i++)
      for (int j = 0; j < y; j++)
        t2[i][j] = dis.readDouble();
    return t2;
  }

  public static double[] readTensor1(DataInputStream dis) throws IOException {
    int x = dis.readInt();
    double[] t1 = new double[x];
    for (int i = 0; i < x; i++)
      t1[i] = dis.readDouble();
    return t1;
  }

  /** Only works for hyper-rectangular tensors */
  public static void writeTensor3(double[][][] t3, DataOutputStream dos) throws IOException {
    int x = t3.length;
    int y = t3[0].length;
    int z = t3[0][0].length;
    dos.writeInt(x);
    dos.writeInt(y);
    dos.writeInt(z);
    for (int i = 0; i < x; i++) {
      assert t3[i].length == y;
      for (int j = 0; j < y; j++) {
        assert t3[i][j].length == z;
        for (int k = 0; k < z; k++)
          dos.writeDouble(t3[i][j][k]);
      }
    }
  }

  /** Only works for rectangular tensors */
  public static void writeTensor2(double[][] t2, DataOutputStream dos) throws IOException {
    int x = t2.length;
    int y = t2[0].length;
    dos.writeInt(x);
    dos.writeInt(y);
    for (int i = 0; i < x; i++) {
      assert t2[i].length == y;
      for (int j = 0; j < y; j++)
        dos.writeDouble(t2[i][j]);
    }
  }

  /** Only works for hyper-cubic tensors */
  public static void writeTensor1(double[] t1, DataOutputStream dos) throws IOException {
    int x = t1.length;
    dos.writeInt(x);
    for (int i = 0; i < x; i++)
      dos.writeDouble(t1[i]);
  }
}
