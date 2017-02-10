
There are only three data files needed (including JAR).
I'll build a docker file later if asked.

This provides just name/triage tf-idf and document/context tf-idf scoring (not attribute features).

Run this to start a server on port 8888:
```
java -Xmx2G -ea -server -cp fnparse.jar \
  edu.jhu.hlt.ikbp.tac.KbpEntitySearchService \
    port 8888 \
    accumulo.instance INSTANCE \
    accumulo.zookeepers ZOOKEEPERS \
    accumulo.user USERNAME \
    accumulo.password PASSWORD \
    wordDocFreq data/df-cms-simpleaccumulo-twolfe-cag1-nhash12-logb20.jser \
    triageFeatureFrequencies data/fce-mostFreq1000000-nhash12-logb20.jser
```

Make sure to fill in the accumulo paramters with the same server that you used to build the index.
If you want to use the index built by Travis (Gigaword5 + Wikipedia), then use:
```
accumulo.instance minigrid
accumulo.zookeepers r8n04.cm.cluster:2181,r8n05.cm.cluster:2181,r8n06.cm.cluster:2181
accumulo.user reader
accumulo.password 'an accumulo reader'
```

If you want some example Java code which calls this service, see
`edu.jhu.hlt.ikbp.tac.KbpEntitySearchServiceExample`

If you want to build this code from source, make sure you have
the most up-to-date version of [fnparse](https://gitlab.hltcoe.jhu.edu/extraction/fnparse) on the ikbp branch
and [tutils](https://gitlab.hltcoe.jhu.edu/twolfe/tutils) on the master branch.

