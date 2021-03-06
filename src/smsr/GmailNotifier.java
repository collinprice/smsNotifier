package smsr;

import java.awt.TrayIcon;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.util.Properties;
import java.util.prefs.Preferences;

import javax.mail.Address;
import javax.mail.AuthenticationFailedException;
import javax.mail.Authenticator;
import javax.mail.BodyPart;
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.NoSuchProviderException;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.Transport;
import javax.mail.event.MessageChangedEvent;
import javax.mail.event.MessageChangedListener;
import javax.mail.event.MessageCountEvent;
import javax.mail.event.MessageCountListener;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.swing.JFrame;

/**
 * GmailNotifier.java
 * 
 * @author Collin Price
 * @contact collin.price@gmail.com
 * @website http://collinprice.com
 * @date 2010-06-22
 */
public class GmailNotifier implements MessageCountListener, MessageChangedListener {

	private static String imapHost = "imap.gmail.com";
	private static String imapPort = "993";
	private static String smtpHost = "smtp.gmail.com";
	private static String smtpPort = "465";
	
	private static Session session;
	private static TrayIcon trayIcon;
	private static KnockThread knocker;
	private Message lastNewMsg;
	private static Folder folder;
	
	public GmailNotifier(TrayIcon trayIcon) throws IllegalStateException, AuthenticationFailedException {
		this.trayIcon = trayIcon;
		connect();
	} // constructor
	
	public static void connect() throws IllegalStateException {
		Preferences prefs = Preferences.userRoot().node("smsSettings");
		
		String user = prefs.get("user", "");
		String pass = prefs.get("pass", "");
		
		session = Session.getInstance(getIMAPProperties());
		
		Store store = null;
		folder = null;
		
		try {
			store = session.getStore("imap");
			store.connect(user, pass);
			
			folder = store.getFolder("Inbox");
			folder.open(Folder.READ_WRITE);
			
			knocker = new KnockThread(folder, trayIcon);
			knocker.start();
			
			folder.addMessageCountListener(new MessageListener(folder));
		} catch (NoSuchProviderException e) {
			System.err.println("imap provider does not exist.");
			e.printStackTrace();
		} catch (AuthenticationFailedException e) {
			System.err.println("imap authentication failure. bad username or password.");
			OptionPanel.getOptionPanel();
			System.out.println("Optionpanel loaded...");
		} catch (MessagingException e) {
			System.err.println(e.getMessage());
			e.printStackTrace();
		}
		
	} // connect
	
	private static Properties getIMAPProperties() {
		Properties props = System.getProperties();
		props.setProperty("mail.imap.host", imapHost);
		props.setProperty("mail.imap.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
		props.setProperty("mail.imap.socketFactory.fallback", "false");
		props.setProperty("mail.imap.socketFactory.port", imapPort);
		return props;
	} // getIMAPProperties
	
	private Properties getSMTPProperties() {
		Preferences prefs = Preferences.userRoot().node("smsSettings");
		Properties props = System.getProperties();
		props.setProperty("mail.transport.protocol", "smtp");
		props.setProperty("mail.smtp.user", prefs.get("user", ""));
		props.setProperty("mail.smtp.host", smtpHost);
		props.setProperty("mail.smtp.password", prefs.get("pass", ""));
		props.setProperty("mail.smtp.socketFactory.port", smtpPort);
		props.setProperty("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
		props.setProperty("mail.smtp.socketFactory.fallback", "false");
		return props;
	} // getSMTPProperties
	
	/* (non-Javadoc)
	 * @see javax.mail.event.MessageCountListener#messagesAdded(javax.mail.event.MessageCountEvent)
	 */
	@Override
	public void messagesAdded(MessageCountEvent e) {
		System.out.println("Received new message.");
		
		Message[] messages = e.getMessages();
		
		for (Message m : messages) {
			processMessage(m);
		}
	} // messagesAdded

	/**
	 * processMessage
	 *
	 * @param m
	 */
	private void processMessage(Message msg) {
		Authenticator auth = new TheAuthenticator();
		Session smtpSession = Session.getInstance(getSMTPProperties(), auth);
		Preferences prefs = Preferences.userRoot().node("smsSettings");
		String emailMe = prefs.get("email", "");
		Message alert = new MimeMessage(smtpSession);

		try {
			alert.setFrom(new InternetAddress(prefs.get("user", "")));
			alert.setRecipient(Message.RecipientType.TO, new InternetAddress(emailMe));
			
			String from = msg.getFrom()[0].toString();
			
			if (from.contains(emailMe)) {
				System.out.println("From Cellphone");
				respondToQuestion(msg, alert);
			} else {
				System.out.println("From others...");
				lastNewMsg = msg;
				alert.setSubject("New Msg");
				from = "From:" + stripEmail(from);
				String subject = "Subject:" + msg.getSubject();
				alert.setText(from + "\n" + subject);
				Address[] recipients = new Address[1];
				recipients[0] = new InternetAddress(emailMe);
				Transport.send(alert);
				System.out.println("Sent sms alert.");
			}
		} catch (MessagingException e1) {
			e1.printStackTrace();
		}
	} // processMessage

	/**
	 * respondToQuestion
	 *
	 * @param msg
	 * @param alert
	 * @param transport 
	 */
	private void respondToQuestion(Message msg, Message alert) {
		try {
			String content = (String) msg.getContent();
			String question = content.substring(0, content.indexOf(" ")).toLowerCase();
			System.out.println("Question: " + question);
			
			if (question.equals("read")) {
				if (lastNewMsg == null) {
					alert.setSubject("Invalid");
					alert.setText("No last message to read.");
					Transport.send(alert);
				} else {
					alert.setSubject("Read");
					Object o = lastNewMsg.getContent();
					String lastContent = "";
					if (o instanceof Multipart) {
						Multipart multi = (Multipart)o;
						BodyPart part = multi.getBodyPart(0);
						lastContent = (String) part.getContent();
					} else {
						lastContent = (String)o;
					}
					
					if (lastContent.length() == 0) {
						alert.setText("No body.");
					} else {
						do {
							if (lastContent.length() <= 133) {
								alert.setText(lastContent);
							} else {
								String piece = lastContent.substring(0, 134);
								lastContent = lastContent.substring(133);
								alert.setText(piece);
							}
							System.out.println(lastContent);
							Transport.send(alert);
						} while (lastContent.length() > 133);
					}
				}
				
				System.out.println("Send alert.");
				msg.setFlag(Flags.Flag.DELETED, true);
				System.out.println("Flag deleted set.");
			} else if (question.equals("count")) {
				alert.setSubject("New Msg Count");
				alert.setText(folder.getUnreadMessageCount() + " unread messages.");
				Transport.send(alert);
			} else if (question.equals("reply")) {
				if (lastNewMsg == null) {
					alert.setSubject("No recent.");
					alert.setText("No last message to respond to.");
					Transport.send(alert);
				} else {
					String reply = question.substring(question.indexOf(" "), question.indexOf("You can contact me at"));
					alert.setSubject("Re: " + lastNewMsg.getSubject());
					Multipart multi = (Multipart)lastNewMsg.getContent();
					BodyPart part = multi.getBodyPart(0);
					String lastContent = (String) part.getContent();
					String message = reply + "\r\n\r\n" + "On " + lastNewMsg.getSentDate().toString() 
											+ ", <" + lastNewMsg.getFrom()[0] + "wrote: \r\n" + lastContent;
					alert.setText(message);
					Transport.send(alert);
				}
			} else {
				// invalid response. valid responses are..
				alert.setSubject("Invalid");
				alert.setText("Not a valid response. Valid responses are: read, count, and reply");
				Transport.send(alert);
			}
			
		} catch (MessagingException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	} // respondToQuestion

	private String stripEmail(String address) {
		if (address.contains("<") && address.contains(">")) {
			address = address.substring(0, address.indexOf("<")-1);
		}
		return address;
	} // stripEmail
	
	/* (non-Javadoc)
	 * @see javax.mail.event.MessageCountListener#messagesRemoved(javax.mail.event.MessageCountEvent)
	 */
	@Override
	public void messagesRemoved(MessageCountEvent e) {
		// not yet implemented
	} // messagesRemoved

	/* (non-Javadoc)
	 * @see javax.mail.event.MessageChangedListener#messageChanged(javax.mail.event.MessageChangedEvent)
	 */
	@Override
	public void messageChanged(MessageChangedEvent e) {
		
	} // messageChanged

	/**
	 * notifyDetector
	 *
	 */
	public void notifyDetector() {
		synchronized(knocker) {
			knocker.notify();
		}
	} // notifyDetector

	/**
	 * tellDetector
	 *
	 */
	public void tellDetector() {
		synchronized(knocker) {
			knocker.forceNotification();
			knocker.notify();
		}
	} // tellDetector

	/**
	 * exit
	 *
	 */
	public static void exit() {
		synchronized(knocker) {
			knocker.stopKnocking();
			knocker.notify();
		}
		try {
			folder.close(false);
			
		} catch (MessagingException e) {
		}
		folder = null;
	} // exit
	
	private class TheAuthenticator extends Authenticator {
		public PasswordAuthentication getPasswordAuthentication() {
			Preferences prefs = Preferences.userRoot().node("smsSettings");
			return new PasswordAuthentication(prefs.get("user", ""), prefs.get("pass", ""));
		} // getPasswordAuthentication
	} // The Authenticator

	/**
	 * reloadData
	 *
	 */
	public static void reloadData() {
		exit();
		connect();
	} // reloadData

} // GmailNotifier
