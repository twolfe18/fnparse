package edu.jhu.hlt.fnparse.datatypes;

public class StringAndIntArrayTuple {
	String[] s;
	int[] i;
	public StringAndIntArrayTuple(String[] s, int[] i){this.s=s; this.i=i;}
	String[] getS(){return s;}
	int[] getI(){return i;}
}