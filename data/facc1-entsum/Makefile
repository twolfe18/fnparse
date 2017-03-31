
#GRID = test2b
GRID = test2
SPLIT = /export/projects/twolfe/entity-summarization/clueweb-linked/train-dev-test

#FNPARSE=/home/travis/code/fnparse
FNPARSE=/home/hltcoe/twolfe/fnparse-build/fnparse

# NOTE: This is a TINY SUBSET of the full data
#FACC1 = /home/travis/code/data/clueweb09-freebase-annotation/extractedAnnotation
#DBPEDIA = /home/travis/code/fnparse/data/dbpedia/
# This is the full data
FACC1 = /export/projects/tto8/ClueWeb/clueweb09-freebase-annotation/extractedAnnotation
DBPEDIA = /export/projects/twolfe/data/dbpedia

entities:
	rsync -avz --delete $(GRID):$(SPLIT)/rare4/ $@

sentences: entities
	java -ea -server -Xmx2G -cp fnparse.jar edu.jhu.hlt.entsum.CluewebLinkedPreprocess \
		mode sentences \
		linkedCluewebRoot $(FACC1) \
		midFile $</mids.dev.txt \
		outputDir $@/dev
	java -ea -server -Xmx2G -cp fnparse.jar edu.jhu.hlt.entsum.CluewebLinkedPreprocess \
		mode sentences \
		linkedCluewebRoot $(FACC1) \
		midFile $</mids.test.txt \
		outputDir $@/test
	java -ea -server -Xmx2G -cp fnparse.jar edu.jhu.hlt.entsum.CluewebLinkedPreprocess \
		mode sentences \
		linkedCluewebRoot $(FACC1) \
		midFile $</mids.train.txt \
		outputDir $@/train

tokenized-sentences: sentences
	java -ea -server -Xmx2G -cp fnparse.jar edu.jhu.hlt.entsum.CluewebLinkedPreprocess \
		mode cwsent2conll \
		sentencesDir $</dev \
		outputDir $@/dev
	java -ea -server -Xmx2G -cp fnparse.jar edu.jhu.hlt.entsum.CluewebLinkedPreprocess \
		mode cwsent2conll \
		sentencesDir $</test \
		outputDir $@/test
	java -ea -server -Xmx2G -cp fnparse.jar edu.jhu.hlt.entsum.CluewebLinkedPreprocess \
		mode cwsent2conll \
		sentencesDir $</train \
		outputDir $@/train

tokenized-sentences/parsed: tokenized-sentences
	#~/code/concrete-parsey/scripts/parsey-docker-wrapper-local.sh \
	#	<$@/dev/raw.conll >$@/dev/parsed.conll
	#~/code/concrete-parsey/scripts/parsey-docker-wrapper-local.sh \
	#	<$@/test/raw.conll >$@/test/parsed.conll
	#~/code/concrete-parsey/scripts/parsey-docker-wrapper-local.sh \
	#	<$@/train/raw.conll >$@/train/parsed.conll
	$(FNPARSE)/data/facc1-entsum/parsejobs.sh $</dev
	$(FNPARSE)/data/facc1-entsum/parsejobs.sh $</test
	$(FNPARSE)/data/facc1-entsum/parsejobs.sh $</train
	mkdir $@
	touch $@/PARSE_FILES_ARE_IN_TOKENIZED_SENTENCES_SUBDIRS

tokenized-sentences/facts-and-types-for-%: tokenized-sentences
	java -ea -server -Xmx2G -cp fnparse.jar edu.jhu.hlt.entsum.DistSupSetup \
		mentionsParent tokenized-sentences/$* \
		freebaseLinks $(DBPEDIA)/freebase_links_en_sortu.ttl.gz \
		infobox $(DBPEDIA)/infobox_properties_en.ttl.gz \
		dbpediaTypes $(DBPEDIA)/instance_types_transitive_en.ttl.gz \
		| tee $@

distsup-infobox/observed-arg-types.jser: tokenized-sentences/facts-and-types-for-train
	mkdir -p distsup-infobox
	java -ea -server -Xmx2G -cp fnparse.jar edu.jhu.hlt.entsum.ObservedArgTypes \
		output $@ \
		entityDirParent tokenized-sentences/train

distsup-infobox/featex-%: distsup-infobox/observed-arg-types.jser
	$(FNPARSE)/data/facc1-entsum/distsup-featex-jobs.sh tokenized-sentences/$* $< false fnparse.jar
	touch $@

distsup-infobox/binary-featex-%: distsup-infobox/observed-arg-types.jser
	$(FNPARSE)/data/facc1-entsum/distsup-featex-jobs.sh tokenized-sentences/$* $< true fnparse.jar
	touch $@


### Old way: create one huge shuffled train file
### problem: this takes forever to create
#distsup-infobox/%.csoaa_ldf.yx: distsup-infobox/featex-%
#	@echo "Shuffling and consolodating $* data..."
#	time cat tokenized-sentences/$*/*/distsup-infobox.csoaa_ldf.yx \
#		| $(FNPARSE)/scripts/multiline-to-singleline /dev/stdin /dev/stdout '|||' \
#		| shuf \
#		| $(FNPARSE)/scripts/singleline-to-multiline /dev/stdin $@ '|||'

### New way: (1) Shuffle the train files for each entity
distsup-infobox/featex-shuf-train: distsup-infobox/featex-train
	$(FNPARSE)/data/facc1-entsum/shufAll-csoaa_ldf-file.sh tokenized-sentences/train 4
	touch $@
### New way: (2) Then iterate over each entity dir, training a model entity-by-entity
distsup-infobox/models: distsup-infobox/featex-shuf-train
	#time vw -q :: --csoaa_ldf m -b 22 --ring_size 4096 -f $@ <$<
	qsub -cwd -j y -o distsup-infobox -N ds-m1 -l "num_proc=1,mem_free=1G,h_rt=24:00:00" \
		$(FNPARSE)/data/facc1-entsum/train-serial.sh \
			tokenized-sentences/train distsup-infobox/model-1stOrder-m-s1.vw "--csoaa_ldf m -b 22" 1
	qsub -cwd -j y -o distsup-infobox -N ds-m2 -l "num_proc=1,mem_free=1G,h_rt=24:00:00" \
		$(FNPARSE)/data/facc1-entsum/train-serial.sh \
			tokenized-sentences/train distsup-infobox/model-2ndOrder-m-s1.vw "--csoaa_ldf m -b 22 -q ::" 1
	qsub -cwd -j y -o distsup-infobox -N ds-mc1 -l "num_proc=1,mem_free=1G,h_rt=24:00:00" \
		$(FNPARSE)/data/facc1-entsum/train-serial.sh \
			tokenized-sentences/train distsup-infobox/model-1stOrder-mc-s1.vw "--csoaa_ldf mc -b 22" 1
	qsub -cwd -j y -o distsup-infobox -N ds-mc2 -l "num_proc=1,mem_free=1G,h_rt=24:00:00" \
		$(FNPARSE)/data/facc1-entsum/train-serial.sh \
			tokenized-sentences/train distsup-infobox/model-2ndOrder-mc-s1.vw "--csoaa_ldf mc -b 22 -q ::" 1
	touch $@

distsup-infobox/predictions-%: distsup-infobox/models
	$(FNPARSE)/data/facc1-entsum/distsup-prediction-jobs.sh tokenized-sentences/$* distsup-infobox/model-1stOrder-m-s1.vw
	$(FNPARSE)/data/facc1-entsum/distsup-prediction-jobs.sh tokenized-sentences/$* distsup-infobox/model-2ndOrder-m-s1.vw
	$(FNPARSE)/data/facc1-entsum/distsup-prediction-jobs.sh tokenized-sentences/$* distsup-infobox/model-1stOrder-mc-s1.vw
	$(FNPARSE)/data/facc1-entsum/distsup-prediction-jobs.sh tokenized-sentences/$* distsup-infobox/model-2ndOrder-mc-s1.vw
	#touch $@


# Summaries will go inline with the rest of the data
# $ENTITY/summary/slot-w100.txt 		a 100-word summary using slots-as-concepts
# $ENTITY/summary/related-w100.txt
# $ENTITY/summary/vanilla-w100.txt
#### For now, these files will all just be "as they should appear in the HIT"
#### TODO Consider what a good intermediate format would be so that we can present the info multiple ways


