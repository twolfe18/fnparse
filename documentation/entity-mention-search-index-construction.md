
To ingest you need to choose a table namespace, which is a prefix for the accumulo tables
created by this ingester. If you use `tableNamespace foo_corpus`, this ingester will expect
that the tables `foo_corpus_f2t`, `foo_corpus_t2c`, and `foo_corpus_c2w` all exist and have
write permissions for the accumulo user you specify.

NOTE: This code *adds to* an index. You remove/delete by removing tables. Because this is append-only
you can run this ingester multiple times, and the index will contain the union of all the comms you
ran on. This code does not deduplicate comms and has undefined behavior when run on two comms with the
same id.

NOTE: This has only been tested on the concretely-annotated-gigaword and concretely-annotated-wikipedia data
which has concrete-stanford annotations. I expect that it will work with arbitrary data which has been annotated
with concrete-stanford, but no promises.

NOTE: The current code generates a misses one big (2x) opportunity for compression.
This changes would require some effort and will not have serious consequences if the data ingested is much smaller
than the CAG+CA-wikipedia data, which is on the order of 12M documents (under 1M docs is not an efficiency concern).
If you are going to ingest something >2M documents, then kindly ask Travis to upgrade the code to be a bit more efficient
(current 12M doc index is ~300G and takes something like 16 hours to create,
but this leaves off two optimizations which I've implemented and not re-run on the full corpus).

```
java -ea -server -Xmx2G \
  edu.jhu.hlt.ikbp.tac.AccumuloIndex \
    command buildIndexRegular \
    tableNamespace foo_corpus \
    accumulo.username USERNAME \
    accumulo.password PASSWORD \
    dataProvider DATA \
    wordDocFreq data/df-cms-simpleaccumulo-twolfe-cag1-nhash12-logb20.jser
```

Where `DATA` is either
`fetch:FETCH_HOST:FETCH_PORT`,
`disk:/path/to/dir/containing/comm-tgz/files/`
or `file:/path/to/single/comms.tgz`.

If you want to build this code from source, make sure you have
the most up-to-date version of [fnparse](https://gitlab.hltcoe.jhu.edu/extraction/fnparse) on the ikbp branch
and [tutils](https://gitlab.hltcoe.jhu.edu/twolfe/tutils) on the master branch.


