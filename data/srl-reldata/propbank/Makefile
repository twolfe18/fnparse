
#FNPARSE = ~/code/fnparse-build/fnparse
FNPARSE = ~/code/fnparse
#FNPARSE = ~/fnparse-build/fnparse
JAR = $(FNPARSE)/target/fnparse-1.0.6-SNAPSHOT-jar-with-dependencies.jar
#DATA = ~/scratch/fnparse-data
DATA = $(FNPARSE)/toydata
#DATA = /export/projects/twolfe/fnparse-data
SHELL = /bin/bash
DATASET = propbank


null-span1.facts:
	echo "def null-span1 <span>" >$@
	echo "schema null-span1 0-0" >>$@

coarsenPos2.rel:
	cp $(FNPARSE)/data/srl-reldata/coarsenPos2.rel $@

grammar.trans:
	cp $(FNPARSE)/data/srl-reldata/grammar/srl-grammar-noFilter.trans grammar.trans
	#cp $(FNPARSE)/data/srl-reldata/grammar/srl-grammar-current.trans grammar.trans

frameTriage4.rel.gz: ../unified/frameTriage4.rel.gz
	zcat ../unified/frameTriage4.rel.gz | head -n 1 >frameTriage4.rel
	zgrep propbank ../unified/frameTriage4.rel.gz >>frameTriage4.rel
	gzip frameTriage4.rel

../unified/frameTriage4.rel.gz:
	java -ea -cp $(JAR) \
		edu.jhu.hlt.uberts.srl.SrlSchemaToRelations \
			outdir $(CURDIR)/../unified \
			overwrite true \
			data.wordnet $(DATA)/wordnet/dict \
			data.propbank.frames $(DATA)/ontonotes-release-5.0-fixed-frames/frames \
			data.framenet.root $(DATA)

../unified/role2.rel.gz: ../unified/frameTriage4.rel.gz

role2.rel.gz:  ../unified/role2.rel.gz
	zcat ../unified/role2.rel.gz | head -n 1 >role2.rel
	zgrep propbank ../unified/role2.rel.gz >>role2.rel
	gzip role2.rel

../unified/coarsenFrame2.rel.gz: ../unified/frameTriage4.rel.gz

coarsenFrame2.rel.gz:  ../unified/coarsenFrame2.rel.gz
	zcat ../unified/coarsenFrame2.rel.gz | head -n 1 >coarsenFrame2.rel
	zgrep propbank ../unified/coarsenFrame2.rel.gz >>coarsenFrame2.rel
	gzip coarsenFrame2.rel


spans.schema.facts.gz:
	python $(FNPARSE)/data/srl-reldata/make-spans.py 300 | gzip -c >$@


all.facts.gz:
	java -Xmx7G -ea -cp $(JAR) \
		edu.jhu.hlt.uberts.srl.FNParseToRelations \
			dataset $(DATASET) \
			outputVals all.facts.gz \
			outputDefs relations.def \
			data.framenet.root $(DATA) \
			data.wordnet $(DATA)/wordnet/dict \
			data.propbank.frames $(DATA)/ontonotes-release-5.0-fixed-frames/frames \
			data.ontonotes5 $(DATA)/LDC2013T19/data/files/data/english/annotations \
			data.propbank.conll $(DATA)/conll-formatted-ontonotes-5.0/conll-formatted-ontonotes-5.0/data

relations.def: all.facts.gz

# Remove any documents/sentences which don't have annotations
# (This is deprecated, FNParse2Relations now filters these out)
	#java -ea -cp $(JAR) \
		edu.jhu.hlt.uberts.io.KeepDocsWithRelation \
		keepRelations argument4,predicate2 \
		relInput all.facts.gz \
		relOutput srl.facts.gz

# Add in parsey mcparseface dependency parses
srl.facts.gz: all.facts.gz
	$(FNPARSE)/scripts/uberts/augment-facts-with-parsey-mcparseface.sh \
		all.facts.gz \
		srl.facts.gz \
		temp-all.facts.conll \
		temp-srl.facts.conll

# Split off train/dev/test
#COE = test2
ids.test.txt:
	#scp $(COE):/export/projects/twolfe/fnparse-output/experiments/conll16/framenet/rel-data/test-set-sentence-ids.txt ids.test.txt
	java -Xmx6G -cp $(JAR) -ea \
		edu.jhu.hlt.fnparse.features.precompute.DumpSentenceIds \
		data $(DATASET) \
		part test \
		output $@ \
		data.framenet.root $(DATA) \
		data.wordnet $(DATA)/wordnet/dict \
		data.propbank.frames $(DATA)/ontonotes-release-5.0-fixed-frames/frames \
		data.ontonotes5 $(DATA)/LDC2013T19/data/files/data/english/annotations \
		data.propbank.conll $(DATA)/conll-formatted-ontonotes-5.0/conll-formatted-ontonotes-5.0/data

ids.traindev.txt:
	java -Xmx6G -cp $(JAR) -ea \
		edu.jhu.hlt.fnparse.features.precompute.DumpSentenceIds \
		data $(DATASET) \
		part train \
		output $@ \
		data.framenet.root $(DATA) \
		data.wordnet $(DATA)/wordnet/dict \
		data.propbank.frames $(DATA)/ontonotes-release-5.0-fixed-frames/frames \
		data.ontonotes5 $(DATA)/LDC2013T19/data/files/data/english/annotations \
		data.propbank.conll $(DATA)/conll-formatted-ontonotes-5.0/conll-formatted-ontonotes-5.0/data

make-train-dev-ids.sh:
	cp $(FNPARSE)/data/srl-reldata/propbank/$@ $@

ids.train.txt: ids.traindev.txt srl.facts.gz make-train-dev-ids.sh
	./make-train-dev-ids.sh

ids.dev.txt: ids.train.txt

#ids.test.txt ids.dev.txt srl.facts.gz
srl.train.facts.gz:
	java -cp $(JAR) -ea \
		edu.jhu.hlt.uberts.io.ManyDocSplitter \
			mode trainDevTest \
			testSetIds ids.test.txt \
			devSetIds ids.dev.txt \
			input srl.train.facts.gz \
			relInput srl.facts.gz \
			trainRelOutput srl.train.facts.gz \
			devRelOutput srl.dev.facts.gz \
			testRelOutput srl.test.facts.gz

srl.dev.facts.gz: srl.train.facts.gz

srl.test.facts.gz: srl.train.facts.gz

srl.dev.shuf.facts.gz: srl.dev.facts.gz
	java -Xmx7G -cp $(JAR) -ea \
		edu.jhu.hlt.uberts.io.ManyDocShuffler \
			seed 9001 \
			input srl.dev.facts.gz \
			output $@

srl.train.shuf%.facts.gz: srl.train.facts.gz
	java -Xmx7G -cp $(JAR) -ea \
		edu.jhu.hlt.uberts.io.ManyDocShuffler \
			seed `echo $@ | md5sum | cut -d' ' -f1 | xargs -I {} python -c 'print int("{}", 16) % (2**16)'` \
			input srl.train.facts.gz \
			output $@

coe:
	rsync -aLvz --delete ./ test3b:/export/projects/twolfe/fnparse-output/experiments/priority-experiments/$(DATASET)/rel-data

all: grammar.trans frameTriage4.rel.gz coarsenFrame2.rel.gz role2.rel.gz spans.schema.facts.gz null-span1.facts coarsenPos2.rel
	make srl.train.shuf0.facts.gz
	make srl.train.shuf1.facts.gz
	make srl.train.shuf2.facts.gz
	make srl.train.shuf3.facts.gz
	make srl.train.shuf4.facts.gz
	make srl.train.shuf5.facts.gz
	make srl.train.shuf6.facts.gz
	make srl.train.shuf7.facts.gz
	make srl.train.shuf8.facts.gz
	make srl.train.shuf9.facts.gz

