FROM node
WORKDIR /graphql
COPY . .
RUN npm install
EXPOSE 4001
#root of MaximoPlus server
ENV SERVER_ROOT = http://localhost:8080
CMD [ "node", "out/server.js" ]
# you have to mount the /graphql/schema directory before running the image
# example: docker run -d -p 4001:4001 --mount type=bind,source=/home/dusan/maximoplus/max-graphql/schema,target=/graphql/schema -e SERVER_ROOT=http://192.168.1.107:8090 maximo-graphql
