package edu.jhu.hlt.fnparse.datatypes;

public class StringAndIntArrayTuple {
	String[] s;
	int[] i;
	public StringAndIntArrayTuple(String[] s, int[] i){this.s=s; this.i=i;}
	public StringAndIntArrayTuple(int[] i, String[] s){this.s=s; this.i=i;}
	public String[] getS(){return s;}
	public int[] getI(){return i;}
}