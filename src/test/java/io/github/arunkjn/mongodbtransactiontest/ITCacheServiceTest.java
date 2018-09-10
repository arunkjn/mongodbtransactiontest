package io.github.arunkjn.mongodbtransactiontest;

import com.mongodb.MongoClientSettings;
import com.mongodb.ReadConcern;
import com.mongodb.TransactionOptions;
import com.mongodb.WriteConcern;
import com.mongodb.client.*;
import com.mongodb.client.model.Filters;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

public class ITCacheServiceTest {

  private MongoClient client, client2;
  private MongoDatabase database, database2;

  @BeforeClass
  public void setup() throws IOException {
    client = MongoClients.create("mongodb://localhost:12000");

    client2 = MongoClients.create("mongodb://localhost:12000");

    database = client.getDatabase("test");

    database2 = client2.getDatabase("test");
  }

  @AfterClass
  public void teardown() throws IOException {
    client.close();
    client2.close();
  }

  @Test(groups = {"integration"})
  public void transactionDatabsae() throws Exception {

    CountDownLatch latch1 = new CountDownLatch(1);
    CountDownLatch latch2 = new CountDownLatch(1);

    Person person1 = new Person("arun", 23);
    Person person2 = new Person("tarun", 25);


    MongoCollection<Person> collection = database.getCollection("transactionDatabaseCache_CACHE", Person.class)
            .withCodecRegistry(CodecRegistries.fromRegistries(
                    MongoClientSettings.getDefaultCodecRegistry(),
                    CodecRegistries.fromProviders(
                            PojoCodecProvider.builder().automatic(true).build()
                    )
            ));

    // creating a document in collection outside transaction
    collection.insertOne(person1);

    // starting a transaction in new thread
    new Thread(() -> {
      ClientSession session = client.startSession();
      session.startTransaction(TransactionOptions.builder().readConcern(ReadConcern.SNAPSHOT).writeConcern(WriteConcern.MAJORITY).build());

      collection.replaceOne(Filters.eq("name", "arun"), person2);

      // waiting for parent thread to read dirty value in middle of transaction before commiting
      latch2.countDown();

      try {
        latch1.await();
      } catch (InterruptedException e) {
        e.printStackTrace();
      }

      session.commitTransaction();
    }).start();

    // waiting for transaction to replace the document with another one, but not commit
    latch2.await();

    // reading the value of document which was previously inserted. Since the transaction is not commited yet, it is expected to receive the same object
    Person person = collection.find(Filters.eq("name", "arun")).first();
    Assert.assertTrue(person != null);
    Assert.assertTrue(person.equals(person1));

    // proceeding with transaction completion
    latch1.countDown();
    Person personx = collection.find(Filters.eq("name", "arun")).first();
    Assert.assertTrue(personx == null);
    Person persony = collection.find(Filters.eq("name", "tarun")).first();
    Assert.assertTrue(persony != null);
    Assert.assertTrue(persony.equals(person2));
  }
}
