
NOTE: This ingest code writes to accumulo tables with the prefix `twolfe_cag1_index2_*`
located at:
```
accumulo.instance minigrid
accumulo.zookeepers r8n04.cm.cluster:2181,r8n05.cm.cluster:2181,r8n06.cm.cluster:2181
```

This current index ALREADY INCLUDES Gigaword and Wikipedia.
If you choose to run this, please tell Travis and/or ask him to modify the
code to allow you to write to your own accumulo instance/tables.

NOTE: This has only been tested on the concretely-annotated-gigaword and concretely-annotated-wikipedia data
which has concrete-stanford annotations. I expect that it will work with arbitrary data which has been annotated
with concrete-stanford, but no promises.

NOTE: The current code generates a couple tables which are not needed and misses some opportunities for compression.
This changes would require some effort and will not have serious consequences if the data ingested is much smaller
than the CAG+CA-wikipedia data, which is on the order of 12M documents (under 1M docs is not an efficiency concern).
If you are going to ingest something >2M documents, then kindly ask Travis to upgrade the code to be a bit more efficient
(changes will cut the usage by a factor of 3 to 5 -- current 12M doc index is ~300G).

```
java -ea -server -Xmx2G \
  edu.jhu.hlt.ikbp.tac.AccumuloIndex \
    command buildIndexRegular \
    accumulo.username USERNAME \
    accumulo.password PASSWORD \
    dataProvider DATA
```

Where `DATA` is either
`fetch:FETCH_HOST:FETCH_PORT`,
`disk:/path/to/dir/containing/comm-tgz/files/`
or `file:/path/to/single/comms.tgz`.

If you want to build this code from source, make sure you have
the most up-to-date version of [fnparse](https://gitlab.hltcoe.jhu.edu/extraction/fnparse) on the ikbp branch
and [tutils](https://gitlab.hltcoe.jhu.edu/twolfe/tutils) on the master branch.


