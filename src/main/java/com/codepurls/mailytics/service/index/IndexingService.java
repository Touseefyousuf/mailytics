package com.codepurls.mailytics.service.index;

import io.dropwizard.lifecycle.Managed;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codepurls.mailytics.config.Config.IndexConfig;
import com.codepurls.mailytics.data.core.Mail;
import com.codepurls.mailytics.data.core.MailFolder;
import com.codepurls.mailytics.data.core.Mailbox;
import com.codepurls.mailytics.service.ingest.MailReader.MailVisitor;
import com.codepurls.mailytics.service.ingest.MailReaderContext;
import com.codepurls.mailytics.service.security.UserService;
import com.codepurls.mailytics.utils.Tuple;

import edu.emory.mathcs.backport.java.util.concurrent.atomic.AtomicInteger;
import edu.emory.mathcs.backport.java.util.concurrent.atomic.AtomicLong;

public class IndexingService implements Managed {
  private static final Logger                       LOG         = LoggerFactory.getLogger("IndexingService");
  private final IndexConfig                         index;
  private final UserService                         userService;
  private final ExecutorService                     indexerPool;
  private final BlockingQueue<Tuple<Mailbox, Mail>> mailQueue;
  private final BlockingQueue<Mailbox>              mboxQueue;
  private final AtomicBoolean                       keepRunning = new AtomicBoolean(true);
  private final Map<Mailbox, IndexWriter>           userIndices;
  private final Version                             version     = Version.LUCENE_4_9;
  private final Analyzer                            analyzer;
  private final Thread                              mailboxVisitor;
  private final Object                              LOCK        = new Object();

  public class IndexWorker implements Callable<AtomicLong> {
    private final AtomicLong counter = new AtomicLong();

    public AtomicLong call() throws Exception {
      try {
        loop();
        LOG.info("IndexWorker stopped, prepared {} docs", counter.get());
      } catch (Exception e) {
        LOG.error("Error indexing mails, ignoring", e);
      }
      return counter;
    }

    private void loop() throws IOException {
      while (keepRunning.get()) {
        try {
          Tuple<Mailbox, Mail> tuple = mailQueue.take();
          if (tuple == null) {
            LOG.info("Received term signal, will quit.");
            break;
          }
          Mailbox mb = tuple.getKey();
          IndexWriter writer = getWriterFor(mb);
          writer.addDocument(MailIndexer.prepareDocument(mb, tuple.getValue()));
        } catch (InterruptedException e) {
          LOG.warn("Interrupted while polling queue, will break", e);
          break;
        }
      }
    }
  }

  public class MailboxVisitor implements Runnable {
    public void run() {
      LOG.info("Starting MailboxVisitor");
      while (keepRunning.get()) {
        try {
          doVisit();
        } catch (InterruptedException e) {
          LOG.error("Interrupted wailing for mailboxes will stop", e);
          break;
        } catch (Exception e) {
          LOG.error("Error during mbox retrieval loop, will ignore", e);
        }
      }
      LOG.info("Stopping MailboxVisitor");
    }

    private void doVisit() throws InterruptedException {
      Mailbox mb = mboxQueue.take();
      LOG.info("Will index new mailbox: {}", mb.name);
      AtomicInteger mails = new AtomicInteger();
      AtomicInteger folders = new AtomicInteger();
      mb.visit(new MailReaderContext(), new MailVisitor() {
        public void onNewMail(Mail mail) {
          mails.incrementAndGet();
          try {
            mailQueue.put(Tuple.of(mb, mail));
          } catch (InterruptedException e) {
            Thread.interrupted();
            throw new RuntimeException(e);
          }
        }

        public void onNewFolder(MailFolder folder) {
          LOG.info("Visiting folder {}", folder.getName());
          folders.incrementAndGet();
        }

        public void onError(Throwable t, MailFolder folder, Mail mail) {
          LOG.error("Error reading mails, mailbox: {}, folder: {}, mail: {}", mb.name, folder.getName(), mail);
        }
      });
      LOG.info("Done visiting mailbox '{}', visited {} folders and {} mails", mb.name, folders.get(), mails.get());
      try {
        IndexWriter writer = getWriterFor(mb);
        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(5));
        LOG.info("Commiting index for mailbox '{}'", mb.name);
        writer.commit();
        LOG.info("Closing index for mailbox '{}'", mb.name);
        writer.close(true);
        userIndices.remove(mb);
        LOG.info("Mailbox '{}' indexed", mb.name);
      } catch (IOException e) {
        LOG.error("Error commiting index for mailbox {}", mb.name, e);
      }
    }
  }

  public IndexingService(IndexConfig index, UserService userService) {
    this.index = index;
    this.userService = userService;
    this.indexerPool = Executors.newFixedThreadPool(index.indexerThreads);
    this.mailQueue = new ArrayBlockingQueue<>(index.indexQueueSize);
    this.mboxQueue = new ArrayBlockingQueue<>(32);
    this.mailboxVisitor = new Thread(new MailboxVisitor(), "mb-visitor");
    this.userIndices = new HashMap<>();
    this.analyzer = new StandardAnalyzer(version);
  }

  public IndexWriterConfig getWriterConfig() {
    IndexWriterConfig cfg = new IndexWriterConfig(version, analyzer);
    return cfg;
  }

  public Directory getIndexDir(Mailbox mb) throws IOException {
    return getIndexDir(index, mb);
  }

  public static Directory getIndexDir(IndexConfig config, Mailbox mb) throws IOException {
    String name = mb.name.toLowerCase();
    name = name.replaceAll("\\W+", "_");
    return FSDirectory.open(new File(config.location, mb.user.username.toLowerCase() + File.separatorChar + name));
  }

  protected IndexWriter getWriterFor(Mailbox mb) throws IOException {
    IndexWriter writer = userIndices.get(mb);
    if (writer == null) {
      synchronized (LOCK) {
        writer = userIndices.get(mb);
        if (writer == null) {
          writer = new IndexWriter(getIndexDir(this.index, mb), getWriterConfig());
          userIndices.put(mb, writer);
        }
      }
    }
    return writer;
  }

  public void start() throws Exception {
    validateIndexDir(index);
    for (int i = 0; i < index.indexerThreads; i++) {
      indexerPool.submit(new IndexWorker());
    }
    mailboxVisitor.start();
    indexerPool.shutdown();
  }

  private void validateIndexDir(IndexConfig index) {
    File loc = new File(index.location);
    if (!loc.exists()) {
      LOG.info("Creating non-existitent index directory {}", loc);
      if (!loc.mkdirs()) { throw new RuntimeException("Unable to create index directory:" + loc); }
    }
    if (!loc.canWrite()) { throw new RuntimeException("Index directory " + loc + " is not writable."); }
  }

  public void stop() throws Exception {
    this.keepRunning.set(false);
    LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(1));
    this.mailboxVisitor.interrupt();
    this.mailQueue.offer(null, 1, TimeUnit.SECONDS);
    this.indexerPool.shutdownNow();
    this.userIndices.forEach((k, w) -> {
      try {
        LOG.info("Closing index writer for mailbox {}", k.name);
        w.close(true);
      } catch (Exception e) {
        LOG.error("Error commiting index of {}", k.username, e);
      }
    });
    this.userIndices.clear();
  }

  public void index(Mailbox mb) {
    mboxQueue.add(mb);
  }

  public IndexConfig getIndex() {
    return index;
  }

  public UserService getUserService() {
    return userService;
  }

  public Version getVersion() {
    return version;
  }

  public Analyzer getAnalyzer() {
    return analyzer;
  }
}
