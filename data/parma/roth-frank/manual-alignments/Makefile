
#AGIGA = /export/common/data/processed/concrete/4.6.10-concretely-annotated/

doc_ids.txt:
	cat stand_off_annotations.* | awk 'NF == 1' | grep -v '^XML' | sort -u >$@

IKBP_JAR = /home/travis/code/fnparse/target/fnparse-1.0.6-SNAPSHOT-jar-with-dependencies.jar

# CS == concrete-stanford
# Make sure you build this first
CS_JAR = /home/travis/code/concrete-stanford/target/concrete-stanford-4.10.2-SNAPSHOT-jar-with-dependencies.jar

# CJ == concrete-java
# Make sure you build this first, probably should be on master
CJ_CONLL_JAR = /home/travis/code/concrete-java/ingesters/conll/target/concrete-ingesters-conll-4.10.3-SNAPSHOT-jar-with-dependencies.jar
CJ_UTIL_JAR = /home/travis/code/concrete-java/util/target/concrete-util-4.10.3-SNAPSHOT-jar-with-dependencies.jar

# CP == concrete-parsey
CP_HOME = /home/travis/code/concrete-parsey

# Get sectioned communications for concrete-stanford
concrete-sectioned: xml-inline-fixed
	mkdir $@
	java -ea -cp $(IKBP_JAR) \
		edu.jhu.hlt.ikbp.RfToRawConcrete \
			xml-inline-fixed \
			concrete-sectioned
	tar -czf concrete-sectioned/all.tgz $@/*.comm

# Tokenize and run Stanford tools
concrete-stanford: concrete-sectioned
	mkdir $@
	java -cp $(CS_JAR) -ea edu.jhu.hlt.concrete.stanford.AnnotateNonTokenizedConcrete \
		concrete-sectioned/all.tgz \
		$@/all.tgz
	tar xf $@/all.tgz -C $@

# Run ConcreteToConllX so we can run concrete-parsey
# Is deserializing concrete really making this that slow?
conllx-raw: concrete-stanford
	mkdir $@
	for f in concrete-stanford/*.comm; do \
		./concrete2conllx-wrapper.sh $$f $@ $(CJ_CONLL_JAR); \
	done

# Run concrete-parsey to get good POS tags and dependency parses
conllx-parsey: conllx-raw
	mkdir $@
	for f in conllx-raw/*.conll; do \
		./parsey-conll-wrapper.sh $$f $@ $(CP_HOME); \
	done

# Ingest parsey conll and stanford section info into communications
concrete-parsey:
	#conllx-parsey
	mkdir $@
	for f in conllx-parsey/*.conll; do \
		./conllx2concrete-wrapper.sh "parsey" $$f conllx-raw $@ $(CJ_CONLL_JAR); \
	done

concrete-parsey-and-stanford: concrete-parsey concrete-stanford
	mkdir $@
	for f in concrete-stanford/*.comm; do \
		./merge-wrapper.sh $$f concrete-parsey $@ $(CJ_UTIL_JAR); \
	done
	tar -czf $@/all.tgz $@/*.comm

