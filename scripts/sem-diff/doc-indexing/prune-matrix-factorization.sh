#!/bin/bash

# Takes (row_index_string, col_index_string, count) rows, tab-separated.
# *_index_string should not contain tabs.
# Reads and writes through stdin and stdout.
# Input values need not be sorted.

# SUM constraints zero out rows or cols based on their sums
# NNZ constraints zero out rows or cols based on the number of non-zero entries

# The following command might be helpful for computing the input from deprels format:
# zcat deprels.txt.gz | awk -F"\t" '{printf("%s\t%s/%s_%s/%s\n", $1, $3, $4, $6, $7)}' | LC_COLLATE=C sort | uniq -c | awk '{printf("%s\t%s\t%d\n", $2, $3, $1)}' >/tmp/a

set -eu -o pipefail

PRUNE_ROW_SUM=$1
PRUNE_ROW_NNZ=$2
PRUNE_COL_SUM=$3
PRUNE_COL_NNZ=$4
TEMP_DIR=$5

AWK_PRUNE_ROW=./scripts/sem-diff/doc-indexing/prune-matrix-by-row.awk

if [[ ! -f $AWK_PRUNE_ROW ]]; then
  echo "can't find awk script: $AWK_PRUNE_ROW"
  echo "did you run from $$FNPARSE?"
  exit 1
fi

# Sort by row
# Prune rows
# Transpose (so cols look like rows)
# Sort by col
# Prune cols
# Transpose (back to input format)
export LC_COLLATE=C
sort -t '	' -k1 -T "$TEMP_DIR" \
  | awk -F"\t" -f $AWK_PRUNE_ROW \
    -v MIN_ROW_NNZ=$PRUNE_ROW_NNZ \
    -v MIN_ROW_SUM=$PRUNE_ROW_SUM \
    -v MIN_ENTRY_COUNT=1 \
  | awk -v OFS='	' -F"\t" '{t=$1; $1=$2; $2=t; print}' \
  | sort -t '	' -k1 -T "$TEMP_DIR" \
  | awk -F"\t" -f $AWK_PRUNE_ROW \
    -v MIN_ROW_NNZ=$PRUNE_COL_NNZ \
    -v MIN_ROW_SUM=$PRUNE_COL_SUM \
    -v MIN_ENTRY_COUNT=1 \
  | awk -v OFS='	' -F"\t" '{t=$1; $1=$2; $2=t; print}'

