
#GRID = test2b
GRID = test2
SPLIT = /export/projects/twolfe/entity-summarization/clueweb-linked/train-dev-test

# NOTE: This is a TINY SUBSET of the full data
#FACC1 = /home/travis/code/data/clueweb09-freebase-annotation/extractedAnnotation
#DBPEDIA = /home/travis/code/fnparse/data/dbpedia/
# This is the full data
FACC1 = /export/projects/tto8/ClueWeb/clueweb09-freebase-annotation/extractedAnnotation
DBPEDIA = /export/projects/twolfe/data/dbpedia

entities:
	rsync -avz --delete $(GRID):$(SPLIT)/rare4/ $@

sentences: entities fnparse.jar
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

parsed-sentences: tokenized-sentences
	~/code/concrete-parsey/scripts/parsey-docker-wrapper-local.sh \
		<$@/dev/raw.conll >$@/dev/parsed.conll
	~/code/concrete-parsey/scripts/parsey-docker-wrapper-local.sh \
		<$@/test/raw.conll >$@/test/parsed.conll
	~/code/concrete-parsey/scripts/parsey-docker-wrapper-local.sh \
		<$@/train/raw.conll >$@/train/parsed.conll


# have one of these folders for each of train/dev/test?
# => just have one file within each for train/dev/test


# Makes binary "this sentence contains some infobox fact" VW training data
features-extracted: parsed-sentences
	@echo "No longer needed: this made binary \"this sentence contains some infobox fact\" VW training data"

dbpedia-distsup: parsed-sentences
	java -ea -server -Xmx2G -cp fnparse.jar edu.jhu.hlt.entsum.DbpediaDistSup \
		mode generateDistSupInstances \
		relevantMids entities/mids.dev.txt \
		freebaseLinks $(DBPEDIA)/freebase_links_en.ttl.gz \
		infoboxFacts $(DBPEDIA)/infobox_properties_en.ttl.gz \
		sentences parsed-sentences/sentences.txt \
		hashes parsed-sentences/hashes.txt \
		conll parsed-sentences/parsed.conll \
		output $@/dev




