
# NOTE: This works on rows, but you can
# always get col functionality by tranposing
# the matrix.

# You can override the defaults using:
# awk -v MIN_ROW_NNZ=4 ...

BEGIN {
  #if (ARGC != 4) {
  #  print ARGC
  #  print "please provide:" > "/dev/stderr";
  #  print "1) MIN_ROW_NNZ" > "/dev/stderr";
  #  print "2) MIN_ROW_SUM" > "/dev/stderr";
  #  print "3) MIN_ENTRY_COUNT" > "/dev/stderr";
  #  exit 1;
  #}
  ## only keep row indices which have at
  ## least this many distinct col indices.
  #MIN_ROW_NNZ = ARGV[1];

  ## only keep row indices which have a
  ## total count (across all col entries)
  ## of at least this value.
  #MIN_ROW_SUM = ARGV[2];

  ## ignore all (row,col) entries which
  ## have a count less than this.
  #MIN_ENTRY_COUNT = ARGV[3];
}

# row, col, count
NF == 3 && $3 >= MIN_ENTRY_COUNT {
  if ($1 != KEY) {
    if (C >= MIN_ROW_SUM && N >= MIN_ROW_NNZ) {
      for (i=0; i<N; i++)
        printf("%s\t%s\t%d\n", KEY, AR[i], CN[i]);
    }
    N = 0;
    C = 0;
    KEY = $1;
  }
  AR[N] = $2;
  CN[N] = $3;
  N++;
  C += $3;
}

END {
  if (C >= MIN_ROW_SUM && N >= MIN_ROW_NNZ) {
    for (i=0; i<N; i++)
      printf("%s\t%s\t%d\n", KEY, AR[i], CN[i]);
  }
}


