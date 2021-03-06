package com.codepurls.mailytics.api.v1.transfer;

import java.util.Date;
import java.util.List;
import java.util.Map;

public class RESTMail {
  public String               id;
  public Date                 date;
  public String               dateString;
  public String               subject, from, bcc, cc, to, body;
  public Map<String, String>  headers;
  public String               folder;
  public int                  attachmentCount;
  public List<RESTAttachment> attachments;
  public String               language;
  public String               userAgent;
  public String               thread_topic;
}
