package edu.jhu.hlt.fnparse.util;

public class ScalaLike {

	public static void println(String s) { System.out.println(s); }

	public static void require(boolean b, String msg) {
		if(!b) throw new IllegalArgumentException(msg);
	}

	public static void require(boolean b) {
		if(!b) throw new IllegalArgumentException();
	}
}
