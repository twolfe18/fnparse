
# Pass in:
# -v shard=42 -v numshard=100

# This one needs to be sharded since it refines by frame, which means
# we will have to keep a table entry for every (y=role, x=(template,feature), refinement=frame),
# which is a lot...

BEGIN {
	#shard = 0;
	#numshard = 10;
	#if (ARGC >= 2) {
	#	print "setting ns" >"/dev/stderr"
	#	shard = ARGV[1];
	#	numshard = ARGV[2];
	#}
	all = 0;
	pos = 0;
	posTaken = 0;
	features = 0;
	printf "shard=%d numshard=%d\n", shard, numshard >"/dev/stderr"
}

$5 != -1 {
	split($5,fr,",");
	role=fr[1];
	frame=fr[3];
	pos++;
	if (frame % numshard == shard) {
		posTaken++;
		features += (NF-6);
		for (i=6; i<=NF; i++) {
			printf("%s\t%s\tf=%s\n", role, $i, frame)
		}
	}
}

{
	all++;
	if (all % 2500 == 0) {
		printf "allLines=%d posLines=%d posLinesTaken=%d features=%d shard=%d numshard=%d\n", all, pos, posTaken, features, shard, numshard >"/dev/stderr"
	}
}


