package edu.jhu.hlt.fnparse.rl.full;

import java.util.Random;

import org.apache.commons.math3.distribution.NormalDistribution;
import org.apache.commons.math3.random.ISAACRandom;
import org.apache.commons.math3.random.MersenneTwister;
import org.apache.commons.math3.random.RandomGenerator;
//import org.uncommons.maths.number.ConstantGenerator;
//import org.uncommons.maths.random.GaussianGenerator;

import edu.jhu.hlt.tutils.MultiTimer;
import edu.jhu.prim.util.random.Prng;

public class GaussianBenchmark {

  public static class Foo implements RandomGenerator {

    @Override
    public boolean nextBoolean() {
      return Prng.nextBoolean();
    }

    @Override
    public void nextBytes(byte[] arg0) {
      throw new RuntimeException();
    }

    @Override
    public double nextDouble() {
      return Prng.nextDouble();
    }

    @Override
    public float nextFloat() {
      return (float) Prng.nextDouble();
    }

    @Override
    public double nextGaussian() {
      throw new RuntimeException();
    }

    @Override
    public int nextInt() {
      return Prng.nextInt();
    }

    @Override
    public int nextInt(int arg0) {
      return Prng.nextInt(arg0);
    }

    @Override
    public long nextLong() {
      return Prng.nextLong();
    }

    @Override
    public void setSeed(int arg0) {
      Prng.seed(arg0);
    }

    @Override
    public void setSeed(int[] arg0) {
      throw new RuntimeException();
    }

    @Override
    public void setSeed(long arg0) {
      Prng.seed(arg0);
    }
  }

  public static void main(String[] args) {
    int n = 10000000;
    MultiTimer t = new MultiTimer();

    Random r1 = new Random(9001);
    NormalDistribution r2 = new NormalDistribution(new MersenneTwister(9001), 0, 1);
    NormalDistribution r3 = new NormalDistribution(new ISAACRandom(9001), 0, 1);
//    NormalDistribution r3 = new NormalDistribution(new Foo(), 0, 1);
    /*
    GaussianGenerator r4 = new GaussianGenerator(
        new ConstantGenerator<>(0d),
        new ConstantGenerator<>(1d),
        new org.uncommons.maths.random.XORShiftRNG());
    GaussianGenerator r5 = new GaussianGenerator(
        new ConstantGenerator<>(0d),
        new ConstantGenerator<>(1d),
        new org.uncommons.maths.random.MersenneTwisterRNG());
    */

    for (int iter = 0; iter < 10; iter++) {
      t.start("java");
      double avg = 0;
      for (int i = 0; i < n; i++)
        avg = avg * 0.9 + 0.1 * r1.nextGaussian();
      t.stop("java");

      t.start("apache.mersenne");
      for (int i = 0; i < n; i++)
        avg = avg * 0.9 + 0.1 * r2.sample();
      t.stop("apache.mersenne");

      t.start("apache.isaac");
      for (int i = 0; i < n; i++)
        avg = avg * 0.9 + 0.1 * r3.sample();
      t.stop("apache.isaac");

//      t.start("custom");
//      for (int i = 0; i < n; i++)
//        avg = avg * 0.9 + 0.1 * r3.sample();
//      t.stop("custom");

      /*
      t.start("uncommons.xor");
      for (int i = 0; i < n; i++)
        avg = avg * 0.9 + 0.1 * r4.nextValue();
      t.stop("uncommons.xor");

      t.start("uncommons.mersenne");
      for (int i = 0; i < n; i++)
        avg = avg * 0.9 + 0.1 * r5.nextValue();
      t.stop("uncommons.mersenne");
      */
    }

    /*
java: <Timer java 9.98 sec and 10 calls total, 0.994 sec/call>
uncommons.xor: <Timer uncommons.xor 13.13 sec and 10 calls total, 1.321 sec/call>
apache.mersenne: <Timer apache.mersenne 7.25 sec and 10 calls total, 0.722 sec/call>
apache.isaac: <Timer apache.isaac 6.56 sec and 10 calls total, 0.656 sec/call>
uncommons.mersenne: <Timer uncommons.mersenne 13.97 sec and 10 calls total, 1.400 sec/call>
total: 50.903
     */
    System.out.println(t);
  }
}
