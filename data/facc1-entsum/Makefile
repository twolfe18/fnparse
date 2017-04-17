
### This makefile handles everything AFTER the entity mids have been chosen.
### See ../build.sh for everything before.

### NOTE: These DEPENDENCIES are more of a SUGGESTION than a real Makefile
### This is because qsub is async, making things difficult to work with Make

#GRID = test2b
GRID = test2

#FNPARSE=/home/travis/code/fnparse
FNPARSE=/home/hltcoe/twolfe/fnparse-build/fnparse

# NOTE: This is a TINY SUBSET of the full data (on my laptop)
#FACC1 = /home/travis/code/data/clueweb09-freebase-annotation/extractedAnnotation
#DBPEDIA = /home/travis/code/fnparse/data/dbpedia/
# This is the full data
FACC1 = /export/projects/tto8/ClueWeb/clueweb09-freebase-annotation/extractedAnnotation
DBPEDIA = /export/projects/twolfe/data/dbpedia

DF = /export/projects/twolfe/sit-search/idf/cms/df-cms-simpleaccumulo-twolfe-cag1-nhash12-logb20.jser

ENT_COUNTS = /export/projects/twolfe/entity-summarization/clueweb-linked/train-dev-test/freebase-mid-mention-frequency.cms.jser

# Grep out the sentences which mention the entities in question.
sentences: entities
	qsub -b y -j y -cwd -o . -N "sent-dev" -l "num_proc=1,mem_free=3G,h_rt=16:00:00" \
		java -ea -server -Xmx2G -cp fnparse.jar edu.jhu.hlt.entsum.CluewebLinkedPreprocess \
			mode sentences \
			linkedCluewebRoot $(FACC1) \
			midFile entities/mids.dev.txt \
			outputDir $@/dev
	qsub -b y -j y -cwd -o . -N "sent-test" -l "num_proc=1,mem_free=3G,h_rt=16:00:00" \
		java -ea -server -Xmx2G -cp fnparse.jar edu.jhu.hlt.entsum.CluewebLinkedPreprocess \
			mode sentences \
			linkedCluewebRoot $(FACC1) \
			midFile entities/mids.test.txt \
			outputDir $@/test
	qsub -b y -j y -cwd -o . -N "sent-train" -l "num_proc=1,mem_free=3G,h_rt=16:00:00" \
		java -ea -server -Xmx2G -cp fnparse.jar edu.jhu.hlt.entsum.CluewebLinkedPreprocess \
			mode sentences \
			linkedCluewebRoot $(FACC1) \
			midFile entities/mids.train.txt \
			outputDir $@/train

# Tokenize the sentences (and do minor filtering like eliminating sentences longer than 80 words).
#tokenized-sentences: sentences
tokenized-sentences:
	qsub -b y -j y -cwd -o . -N "tok-dev" -l "num_proc=1,mem_free=3G,h_rt=16:00:00" \
		java -ea -server -Xmx2G -cp fnparse.jar edu.jhu.hlt.entsum.CluewebLinkedPreprocess \
			mode cwsent2conll \
			sentencesDir sentences/dev \
			outputDir $@/dev
	qsub -b y -j y -cwd -o . -N "tok-test" -l "num_proc=1,mem_free=3G,h_rt=16:00:00" \
		java -ea -server -Xmx2G -cp fnparse.jar edu.jhu.hlt.entsum.CluewebLinkedPreprocess \
			mode cwsent2conll \
			sentencesDir sentences/test \
			outputDir $@/test
	qsub -b y -j y -cwd -o . -N "tok-train" -l "num_proc=1,mem_free=3G,h_rt=16:00:00" \
		java -ea -server -Xmx2G -cp fnparse.jar edu.jhu.hlt.entsum.CluewebLinkedPreprocess \
			mode cwsent2conll \
			sentencesDir sentences/train \
			outputDir $@/train

# Dependency parse the sentences
#tokenized-sentences/parsed: tokenized-sentences
tokenized-sentences/parsed:
	$(FNPARSE)/data/facc1-entsum/parsejobs.sh tokenized-sentences/dev
	$(FNPARSE)/data/facc1-entsum/parsejobs.sh tokenized-sentences/test
	$(FNPARSE)/data/facc1-entsum/parsejobs.sh tokenized-sentences/train
	#mkdir $@
	#touch $@/PARSE_FILES_ARE_IN_TOKENIZED_SENTENCES_SUBDIRS

# Intersect relevant entities with KB to get fact types (not grounded)
#tokenized-sentences/facts-and-types-for-%: tokenized-sentences
tokenized-sentences/facts-and-types-for-%:
	java -ea -server -Xmx2G -cp fnparse.jar edu.jhu.hlt.entsum.DistSupSetup \
		mentionsParent tokenized-sentences/$* \
		freebaseLinks $(DBPEDIA)/freebase_links_en_sortu.ttl.gz \
		infobox $(DBPEDIA)/infobox_properties_en.ttl.gz \
		dbpediaTypes $(DBPEDIA)/instance_types_transitive_en.ttl.gz \
		| tee $@

tokenized-sentences/all-facts-and-types:
	qsub -cwd -b y -j y -o . -N 'facts-train' -l 'mem_free=3G,num_proc=1,h_rt=6:00:00' \
		java -ea -server -Xmx2G -cp fnparse.jar edu.jhu.hlt.entsum.DistSupSetup \
			mentionsParent tokenized-sentences/train \
			freebaseLinks $(DBPEDIA)/freebase_links_en_sortu.ttl.gz \
			infobox $(DBPEDIA)/infobox_properties_en.ttl.gz \
			dbpediaTypes $(DBPEDIA)/instance_types_transitive_en.ttl.gz
	qsub -cwd -b y -j y -o . -N 'facts-test' -l 'mem_free=3G,num_proc=1,h_rt=6:00:00' \
		java -ea -server -Xmx2G -cp fnparse.jar edu.jhu.hlt.entsum.DistSupSetup \
			mentionsParent tokenized-sentences/test \
			freebaseLinks $(DBPEDIA)/freebase_links_en_sortu.ttl.gz \
			infobox $(DBPEDIA)/infobox_properties_en.ttl.gz \
			dbpediaTypes $(DBPEDIA)/instance_types_transitive_en.ttl.gz
	qsub -cwd -b y -j y -o . -N 'facts-dev' -l 'mem_free=3G,num_proc=1,h_rt=6:00:00' \
		java -ea -server -Xmx2G -cp fnparse.jar edu.jhu.hlt.entsum.DistSupSetup \
			mentionsParent tokenized-sentences/dev \
			freebaseLinks $(DBPEDIA)/freebase_links_en_sortu.ttl.gz \
			infobox $(DBPEDIA)/infobox_properties_en.ttl.gz \
			dbpediaTypes $(DBPEDIA)/instance_types_transitive_en.ttl.gz

### # NOTE: Not currently necessary.
### # Count how many times a relations subj/obj has a certain type.
### # This was used when I thought I needed to prune subj/obj identity features, but PMI seems to do this for me.
### distsup-infobox/observed-arg-types.jser: tokenized-sentences/facts-and-types-for-train
### 	mkdir -p distsup-infobox
### 	java -ea -server -Xmx2G -cp fnparse.jar edu.jhu.hlt.entsum.ObservedArgTypes \
### 		output $@ \
### 		entityDirParent tokenized-sentences/train
### 
### # NOTE: Not currently necessary.
### # Extract csoaa_ldf features for vowpal wabbit.
### # This entire csoaa_ldf pipeline was not successful, use PMI instead.
### distsup-infobox/featex-%: distsup-infobox/observed-arg-types.jser
### 	$(FNPARSE)/data/facc1-entsum/distsup-featex-jobs.sh tokenized-sentences/$* $< false fnparse.jar
### 	touch $@
### 
### ### Old way: create one huge shuffled train file
### ### problem: this takes forever to create
### #distsup-infobox/%.csoaa_ldf.yx: distsup-infobox/featex-%
### #	@echo "Shuffling and consolodating $* data..."
### #	time cat tokenized-sentences/$*/*/distsup-infobox.csoaa_ldf.yx \
### #		| $(FNPARSE)/scripts/multiline-to-singleline /dev/stdin /dev/stdout '|||' \
### #		| shuf \
### #		| $(FNPARSE)/scripts/singleline-to-multiline /dev/stdin $@ '|||'
### 
### ### New way: (1) Shuffle the train files for each entity
### distsup-infobox/featex-shuf-train: distsup-infobox/featex-train
### 	$(FNPARSE)/data/facc1-entsum/shufAll-csoaa_ldf-file.sh tokenized-sentences/train 4
### 	touch $@
### ### New way: (2) Then iterate over each entity dir, training a model entity-by-entity
### distsup-infobox/models: distsup-infobox/featex-shuf-train
### 	#time vw -q :: --csoaa_ldf m -b 22 --ring_size 4096 -f $@ <$<
### 	qsub -cwd -j y -o distsup-infobox -N ds-m1 -l "num_proc=1,mem_free=1G,h_rt=24:00:00" \
### 		$(FNPARSE)/data/facc1-entsum/train-serial.sh \
### 			tokenized-sentences/train distsup-infobox/model-1stOrder-m-s1.vw "--csoaa_ldf m -b 22" 1
### 	qsub -cwd -j y -o distsup-infobox -N ds-m2 -l "num_proc=1,mem_free=1G,h_rt=24:00:00" \
### 		$(FNPARSE)/data/facc1-entsum/train-serial.sh \
### 			tokenized-sentences/train distsup-infobox/model-2ndOrder-m-s1.vw "--csoaa_ldf m -b 22 -q ::" 1
### 	qsub -cwd -j y -o distsup-infobox -N ds-mc1 -l "num_proc=1,mem_free=1G,h_rt=24:00:00" \
### 		$(FNPARSE)/data/facc1-entsum/train-serial.sh \
### 			tokenized-sentences/train distsup-infobox/model-1stOrder-mc-s1.vw "--csoaa_ldf mc -b 22" 1
### 	qsub -cwd -j y -o distsup-infobox -N ds-mc2 -l "num_proc=1,mem_free=1G,h_rt=24:00:00" \
### 		$(FNPARSE)/data/facc1-entsum/train-serial.sh \
### 			tokenized-sentences/train distsup-infobox/model-2ndOrder-mc-s1.vw "--csoaa_ldf mc -b 22 -q ::" 1
### 	touch $@
### 
### distsup-infobox/predictions-%: distsup-infobox/models
### 	$(FNPARSE)/data/facc1-entsum/distsup-prediction-jobs.sh tokenized-sentences/$* distsup-infobox/model-1stOrder-m-s1.vw
### 	$(FNPARSE)/data/facc1-entsum/distsup-prediction-jobs.sh tokenized-sentences/$* distsup-infobox/model-2ndOrder-m-s1.vw
### 	$(FNPARSE)/data/facc1-entsum/distsup-prediction-jobs.sh tokenized-sentences/$* distsup-infobox/model-1stOrder-mc-s1.vw
### 	$(FNPARSE)/data/facc1-entsum/distsup-prediction-jobs.sh tokenized-sentences/$* distsup-infobox/model-2ndOrder-mc-s1.vw
### 	#touch $@

# Ground out KB facts in sentences and extract features from the mentions.
#distsup-infobox/binary-featex-%: distsup-infobox/observed-arg-types.jser
distsup-infobox/binary-featex-%:
	#$(FNPARSE)/data/facc1-entsum/distsup-featex-jobs.sh tokenized-sentences/$* $< true fnparse.jar
	### NOTE: For binary features, we DO NOT need observed arg types
	$(FNPARSE)/data/facc1-entsum/distsup-featex-jobs.sh tokenized-sentences/$* none true fnparse.jar
	touch $@

distsup-infobox/all-binary-featex:
	mkdir distsup-infobox
	make distsup-infobox/binary-featex-train
	make distsup-infobox/binary-featex-test
	make distsup-infobox/binary-featex-dev

# Given features and distsup(infobox) labels, find the features with the highest PMI for each relation.
# This script launches two ways of doing the job: entities-as-instances and extractions-as-instances.
#feature-selection: distsup-infobox/binary-featex-train
feature-selection:
	mkdir $@
	$(FNPARSE)/data/facc1-entsum/featsel-mi-jobs.sh

# Build a simple model for identifying odd/junk sentences.
# This needs nothing beyond parsing.
# This is used to filter out sentences during summarization.
odd-sentence-score.jser:
	java -ea -server -Xmx6G -cp fnparse.jar \
		edu.jhu.hlt.entsum.OddSentenceScore \
			outputJser $@ \
			wordDocFreq $(DF) \
			entityDirParent tokenized-sentences/train \
			minCount 100

# Build summaries for entities using [entities, slots, ngrams].
# Calls SlotsAsConcepts, which calls Gurobi, so it probably won't work on the grid.
summaries/infobox-%: distsup-infobox/binary-featex-% feature-selection
	$(FNPARSE)/data/facc1-entsum/summarize-pmi.sh tokenized-sentences/$* $(DF) $(ENT_COUNTS) fnparse.jar

# Convert summaries in jser format to one csv file (with the fields needed for the hit).
summaries/hit-unlab-%.csv: summaries/infobox-%
	java -ea -server -cp fnparse.jar \
		edu.jhu.hlt.entsum.Summary \
			summary true \
			hit true \
			entityDirParent tokenized-sentences/$$ \
			outputCsv $@


