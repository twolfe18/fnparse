package edu.jhu.hlt.fnparse.rl.full;

import java.io.BufferedReader;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;

/**
 * A way to get primes (from disk).
 *
 * TODO Consider using {@link java.math.BigInteger#probablePrime(int, Random)} instead.
 */
public class Primes {

  // the 1M-th prime is 15,485,863 -- no where near Integer.MAX_VALUE
  private int[] primes;

  public Primes(ExperimentProperties config) {
//    this(config.getExistingFile("primesFile", new File("data/primes/primes1.byLine.txt.gz")),
//        new Random(config.getInt("seed", 9001)));
    this(config.getExistingFile("primesFile", new File("data/primes/primes1.byLine.txt.gz")));
  }

  /**
   * @param f has one prime per line e.g. data/primes/primes1.byLine.txt.gz
   * @param shuffleWith = null means don't shuffle, otherwise do.
   */
  public Primes(File f) {
    List<Integer> l = new ArrayList<>();
    try (BufferedReader r = FileUtil.getReader(f)) {
      for (String line = r.readLine(); line != null; line = r.readLine())
        l.add(Integer.parseInt(line));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
//    if (shuffleWith != null)
//      Collections.shuffle(l, shuffleWith);
    primes = new int[l.size()];
    for (int i = 0; i < primes.length; i++)
      primes[i] = l.get(i);
  }

  public void shuffle(Random r) {
    throw new RuntimeException("implement me");
  }

  public int get(int i) {
    return primes[i];
  }

  public int size() {
    return primes.length;
  }
}
