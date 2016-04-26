
BEGIN {
	pos = 0;
	neg = 0;
	features = 0;
}

{
	y = 0
	if ($5 != -1) {
		y = 1
		pos++;
	} else {
		neg++;
	}
	features += (NF-6);
	for (i=6; i<=NF; i++) {
		#printf("%s\t%s\tf=%s\n", y, $i, refinement);
		printf("%s\t%s\tna\n", y, $i);
	}

	if (pos + neg % 5000 == 0) {
		printf "posLines=%d negLines=%d features=%d\n", pos, neg, features >"/dev/stderr"
	}
}



