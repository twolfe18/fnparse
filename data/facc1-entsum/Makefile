
GRID = test2b
SPLIT = /export/projects/twolfe/entity-summarization/clueweb-linked/train-dev-test

# NOTE: This is a TINY SUBSET of the full data
FACC1 = /home/travis/code/data/clueweb09-freebase-annotation/extractedAnnotation
DBPEDIA = /home/travis/code/fnparse/data/dbpedia/

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

parsed-sentences: sentences
	java -ea -server -Xmx2G -cp fnparse.jar edu.jhu.hlt.entsum.CluewebLinkedPreprocess \
		mode cwsent2conll \
		sentencesDir $< \
		outputDir $@
	@echo "parsing..."
	~/code/concrete-parsey/scripts/parsey-docker-wrapper-local.sh \
		<$@/raw.conll >$@/parsed.conll


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




