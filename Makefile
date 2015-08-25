
jar:
	rm -f target/*.jar
	mvn compile assembly:single -DskipTests

install:
	mvn install -DskipTests -Dgpg.skip=true

