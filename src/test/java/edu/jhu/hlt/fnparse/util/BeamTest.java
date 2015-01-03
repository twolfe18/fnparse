package edu.jhu.hlt.fnparse.util;

import static org.junit.Assert.*;

import org.junit.Test;

public class BeamTest {

  @Test
  public void test0() {
    Beam<String> b = new Beam<>(2);
    assertEquals(0, b.getSize());
    b.push("foo", 1d);
    assertEquals(1, b.getSize());
    assertEquals("foo", b.pop());
    assertEquals(0, b.getSize());
    b.push("bar", 1d);
    b.push("baz", -1d);
    assertEquals("bar", b.pop());
    b.push("s2", 2d);
    b.push("s3", 3d);
    b.push("s4", 4d);
    b.push("s5", 5d);
    assertEquals(2, b.getSize());
    assertEquals(2, b.getWidth());
    assertEquals("s5", b.pop());
    assertEquals(1, b.getSize());
    assertEquals(2, b.getWidth());
    assertEquals("s4", b.pop());
    assertEquals(0, b.getSize());
    assertEquals(2, b.getWidth());
  }
}
