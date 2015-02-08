package edu.jhu.hlt.fnparse.util;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.function.Consumer;

import edu.jhu.hlt.fnparse.datatypes.Span;

public class Generator<T> implements Iterable<T> {
  private Consumer<Consumer<T>> yieldBlock;

  public Generator(Consumer<Consumer<T>> yieldBlock) {
    this.yieldBlock = yieldBlock;
  }
  
  static class It<R> implements Iterator<R> {
    private final BlockingQueue<R> q;
    private final Object terminator;
    private final Thread self;
    private R nextVal;
    public It(BlockingQueue<R> q, Object terminator, Thread self) {
      //System.out.println("[init] terminator=" + terminator);
      this.q = q;
      this.terminator = terminator;
      this.self = self;
      advance();
    }
    private void advance() {
      assert nextVal != terminator;
      try {
        //System.out.println("about to advance, old val=" + nextVal);
        nextVal = q.take();
        //System.out.println("just advanced, new val=" + nextVal);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
    @Override
    public boolean hasNext() {
      //System.out.println("[hasNext] nextVal=" + nextVal + " terminator=" + terminator);
      return nextVal != terminator;
    }
    @Override
    public R next() {
      //System.out.println("[next] nextVal=" + nextVal + " terminator=" + terminator);
      R r = nextVal;
      if (nextVal != terminator)
        advance();
      return r;
    }
    public void done() {
      try {
        self.interrupt();
      } catch (Exception e) {
        e.printStackTrace(System.err);
      }
    }
  }

  //public Stream<T> getStream() {
  @Override
  public It<T> iterator() {
    //final ArrayBlockingQueue<T> q = new ArrayBlockingQueue<T>(10);
    final SynchronousQueue<T> q = new SynchronousQueue<T>(true);
    final Consumer<T> c = new Consumer<T>() {
      @Override
      public void accept(T t) {
        //System.out.println("consumer received " + t);
        try {
          q.put(t);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
    };
    //final AtomicBoolean done = new AtomicBoolean(false);
    final Object terminator = this;
    final Thread t = new Thread(new Runnable() {
      @Override
      public void run() {
        yieldBlock.accept(c);
        //done.set(true);
        //System.out.println("sending terminator");
        c.accept((T) terminator);
      }
    });
    t.start();

    return new It<>(q, terminator, t);

//    Supplier<T> s = new Supplier<T>() {
//      @Override
//      public T get() {
//        try {
//          return q.take();
//        } catch (Exception e) {
//          throw new RuntimeException(e);
//        }
//      }
//    };
//    return Stream.generate(s);
  }

  public static void proofOfConcept() {
    System.out.println("proof of concept");
    Generator<Integer> g = new Generator<>(yield -> {
      for (int i = 0; i < 100; i++) {
        System.out.println("running loop at i=" + i);
        yield.accept(i);
      }
    });
    System.out.println("calling getStream...");
    //Stream<Integer> s = g.getStream().limit(10);
    //System.out.println(s.collect(Collectors.toList()));

//    for (int i : g) {
//      if (i > 10) break;  // PROBLEM: terminator never gets through!
//      System.out.println(i);
//    }
    
    It<Integer> it = g.iterator();
    while (it.hasNext()) {
      int i = it.next();
      if (i > 10)
        it.done();
      else
        System.out.println(i);
    }
  }

  // See if all of the generator overhead is costly...
  public static void benchmark(int n, int sentLen) {
    System.out.println("benchmark(" + n + ", " + sentLen + ")");
    int seed = 9001;
    RandomSpan randSpan = new RandomSpan();

    Timer a = new Timer();
    a.start();
    List<Span> all = new ArrayList<>();
    randSpan.setSeed(seed);
    for (int i = 0; i < n; i++)
      all.add(randSpan.draw(sentLen));
    a.stop();
    System.out.println("vanilla: " + a.totalTimeInMilliseconds() + " ms");

    Timer b = new Timer();
    b.start();
    Generator<Span> g = new Generator<Span>(yield -> {
      randSpan.setSeed(seed);
      for (int i = 0; i < n; i++) {
        //System.out.println("g(" + i + ", " + j + ")");
        yield.accept(randSpan.draw(sentLen));
      }
      //System.out.println("done yielding");
    });
//    Stream<Span> s = g.getStream();
//    System.out.println(s.isParallel());
//    System.out.println(s.count());
//    System.out.println(s.count());
//    List<Span> all2 = s.collect(Collectors.toList());
    List<Span> all2 = new ArrayList<>();
    for (Span s : g) all2.add(s);
    b.stop();
    System.out.println("generator: " + b.totalTimeInMilliseconds() + " ms");

    if (!all.equals(all2)) {
      System.out.flush();
      System.out.println("all: " + all);
      System.out.println("all2: " + all2);
      System.out.flush();
      throw new RuntimeException();
    }
    System.out.println("benchmark(" + n + ", " + sentLen + ") done");
  }

  public static void main(String[] args) {
    proofOfConcept();
    for (int i = 0; i < 22; i++) {
      int n = 1 << i;
      System.out.println("n=" + n);
      benchmark(n, 100);
    }
    System.out.println("done");
  }
}