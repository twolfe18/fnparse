package edu.jhu.hlt.fnparse.datatypes;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Serializable;


public class LexicalUnit implements Serializable {

	public final String word;
	public final String pos;
	private transient String fullStr;
	public final int hc;
	
	public LexicalUnit(String word, String pos) {
		if(word == null)
			throw new IllegalArgumentException();
		if(pos == null)
			throw new IllegalArgumentException();
		this.word = word;
		this.pos = pos.toUpperCase();
		this.hc = this.word.hashCode() ^ this.pos.hashCode();
	}
	
	public String getFullString() {
		if(fullStr == null)
			fullStr = word + "." + pos;
		return fullStr;
	}
	
	public String toString() { return String.format("<LU %s.%s>", word, pos); }
	
	@Override
	public int hashCode() { return hc; }
	
	@Override
	public boolean equals(Object other) {
		if(other instanceof LexicalUnit) {
			LexicalUnit olu = (LexicalUnit) other;
			return word.equals(olu.word) && pos.equals(olu.pos);
		}
		else return false;
	}
	
	
	
	
	// methods for reading/writing from/to a Data(In|Out)putStream
	
	public static void writeTo(LexicalUnit lu, DataOutputStream dos) throws IOException {
		dos.writeUTF(lu.word);
		dos.writeUTF(lu.pos);
	}
	
	public static LexicalUnit readFrom(DataInputStream dis) throws IOException {
		String w = dis.readUTF();
		String p = dis.readUTF();
		return new LexicalUnit(w, p);
	}
}
