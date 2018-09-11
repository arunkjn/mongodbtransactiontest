package io.github.arunkjn.mongodbtransactiontest;

import com.mongodb.MongoClientSettings;
import com.mongodb.ReadConcern;
import com.mongodb.TransactionOptions;
import com.mongodb.WriteConcern;
import com.mongodb.client.*;
import com.mongodb.client.model.Filters;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

public class ITCacheServiceTest {

  private MongoClient client;
  private MongoDatabase database;
  private MongoCollection<Person> collection, collection1;

  @BeforeClass
  public void setup() throws IOException {
    client = MongoClients.create("mongodb://localhost:12000");

    database = client.getDatabase("test");

    collection = database.getCollection("person", Person.class)
            .withCodecRegistry(CodecRegistries.fromRegistries(
                    MongoClientSettings.getDefaultCodecRegistry(),
                    CodecRegistries.fromProviders(
                            PojoCodecProvider.builder().automatic(true).build()
                    )
            ));

    collection1 = database.getCollection("person_second", Person.class)
            .withCodecRegistry(CodecRegistries.fromRegistries(
                    MongoClientSettings.getDefaultCodecRegistry(),
                    CodecRegistries.fromProviders(
                            PojoCodecProvider.builder().automatic(true).build()
                    )
            ));
  }

  @AfterClass
  public void teardown() throws IOException {
    client.close();
  }

  @Test(groups = {"integration"})
  public void transactionDatabsae() throws Exception {

    CountDownLatch latch1 = new CountDownLatch(1);
    CountDownLatch latch2 = new CountDownLatch(1);
    CountDownLatch latch3 = new CountDownLatch(1);

    Person person1 = new Person("arun", 23);
    Person person2 = new Person("tarun", 25);

    // creating a document in collection outside transaction
    collection.insertOne(person1);

    // starting a transaction in new thread
    new Thread(() -> {
      ClientSession session = client.startSession();
      session.startTransaction(TransactionOptions.builder().readConcern(ReadConcern.SNAPSHOT).writeConcern(WriteConcern.MAJORITY).build());

      collection.replaceOne(session, Filters.eq("name", "arun"), person2);

      // waiting for parent thread to read dirty value in middle of transaction before commiting
      latch2.countDown();

      try {
        latch1.await();
      } catch (InterruptedException e) {
        e.printStackTrace();
      }

      session.commitTransaction();
      latch3.countDown();
    }).start();

    // waiting for transaction to replace the document with another one, but not commit
    latch2.await();

    // reading the value of document which was previously inserted. Since the transaction is not commited yet, it is expected to receive the same object
    Person person = collection.find(Filters.eq("name", "arun")).first();
    Assert.assertTrue(person != null);
    Assert.assertTrue(person.equals(person1));

    // proceeding with transaction completion
    latch1.countDown();

    latch3.await();
    Person personx = collection.find(Filters.eq("name", "arun")).first();
    Assert.assertTrue(personx == null);
    Person persony = collection.find(Filters.eq("name", "tarun")).first();
    Assert.assertTrue(persony != null);
    Assert.assertTrue(persony.equals(person2));
  }


  @Test(groups = {"integration"})
  public void staleReadInsideTransaction() throws Exception {

    CountDownLatch latch1 = new CountDownLatch(1);
    CountDownLatch latch2 = new CountDownLatch(1);
    CountDownLatch latch3 = new CountDownLatch(1);

    Person person1 = new Person("arun", 23);

    // creating a document in collection outside transaction
    collection1.insertOne(person1);

    // starting a transaction in new thread
    new Thread(() -> {
      ClientSession session = client.startSession();
      session.startTransaction(TransactionOptions.builder().readConcern(ReadConcern.SNAPSHOT).writeConcern(WriteConcern.MAJORITY).build());

      /**
       * reading a document and creating a new document using first document.
       * This is a causal relationship.
       * If this value is modified from outside while the transaction is running,
       * we expect some sort of error while commiting the transaction
       */
      Person person = collection1.find(session, Filters.eq("name", "arun")).first();
      latch2.countDown();
      try {
        latch1.await();
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
      Person person3 = new Person("tarun", person.getAge() +10);
      collection1.insertOne(session, person3);

      session.commitTransaction();
      latch3.countDown();
    }).start();

    latch2.await();
    Person person = collection1.find(Filters.eq("name", "arun")).first();
    person.setAge(30);
    collection1.replaceOne(Filters.eq("name", "arun"), person);
    latch1.countDown();

    Person person2 = collection1.find(Filters.eq("name", "tarun")).first();
    Assert.assertTrue(person2 != null);
    System.out.println(person2.toString());
    Assert.assertTrue(person2.getAge().equals(40));

  }
}
