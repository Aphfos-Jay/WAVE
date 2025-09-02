# 1단계: 빌드
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests clean package

# 2단계: 실행
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/*.jar /app/app.jar
EXPOSE 8080
CMD ["java", "-jar", "/app/app.jar"]

#빌드시 에러뜨면 아래코드로 바꾸고 보기
# 1단계: 빌드
#FROM maven:3.9.8-eclipse-temurin-17 AS build           
#WORKDIR /app

# 의존성 캐시를 위해 pom.xml만 먼저 복사
#COPY pom.xml .                                        

# BuildKit 캐시 사용: 의존성 다운로드 시간을 줄임
#RUN --mount=type=cache,target=/root/.m2 \
#    mvn -B -ntp -DskipTests dependency:go-offline      

# 실제 소스 복사
#COPY src ./src

# 패키징(테스트 스킵). -e -X 로 상세 로그(문제 원인) 확인
#RUN --mount=type=cache,target=/root/.m2 \
#    mvn -B -e -X -DskipTests clean package

# 2단계: 실행
#FROM eclipse-temurin:17-jre
#WORKDIR /app

# 실행할 JAR을 명확히 지정 (shade로 fat-jar를 만든 경우 예시)
#COPY --from=build /app/target/*-jar-with-dependencies.jar /app/app.jar

#EXPOSE 8080
#ENTRYPOINT ["java", "-jar", "/app/app.jar"]
