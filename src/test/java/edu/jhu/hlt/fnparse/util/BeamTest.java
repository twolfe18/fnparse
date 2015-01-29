package edu.jhu.hlt.fnparse.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import edu.jhu.hlt.fnparse.util.Beam.BeamN;

public class BeamTest {

  @Test
  public void test0() {
    Beam<String> b = new BeamN<>(2);
    assertEquals(0, b.size());
    b.push("foo", 1d);
    assertEquals(1, b.size());
    assertEquals("foo", b.pop());
    assertEquals(0, b.size());
    b.push("bar", 1d);
    b.push("baz", -1d);
    assertEquals("bar", b.pop());
    b.push("s2", 2d);
    b.push("s3", 3d);
    b.push("s4", 4d);
    b.push("s5", 5d);
    assertEquals(2, b.size());
    assertEquals(2, b.width());
    assertEquals("s5", b.pop());
    assertEquals(1, b.size());
    assertEquals(2, b.width());
    assertEquals("s4", b.pop());
    assertEquals(0, b.size());
    assertEquals(2, b.width());
  }
}
