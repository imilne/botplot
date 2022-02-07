#!/mnt/cluster/apps/java/13.0.2/bin/java --source 11

import java.io.*;
import java.net.*;
import java.text.*;
import java.util.*;
import java.util.Map.Entry;
import javax.xml.parsers.*;
import org.w3c.dom.*;

public class AccessTracker
{
	// "Right now"
	private static final long NOW = System.currentTimeMillis();
	// "One week ago"
	private static final long PERIOD = NOW - (7*24*60*60*1000);

	private HashMap<String,IPAddress> addresses = new HashMap<>();

	public static void main(String[] args)
		throws Exception
	{
		AccessTracker tracker = new AccessTracker();
		tracker.run();
	}

	private void run()
		throws Exception
	{
		loadDB();

		readLogs("last.txt", true);
		readLogs("lastb.txt", false);
		readSecure("secure.txt");
		clearOldEntries();

		populateIPs();

		createMapFile();

		saveDB();
	}

	private void readLogs(String filename, boolean usePass)
		throws Exception
	{
		BufferedReader in = new BufferedReader(new FileReader(filename));

		// Skip the header line
		String str = in.readLine();

		while ((str = in.readLine()) != null)
		{
			if (str.isEmpty())
				continue;

			String[] tokens = str.split("\\s+");

			// Skip on non standard lines
			if (tokens[0].equals("reboot") || tokens[2].startsWith(":pts"))
				continue;
			// Or internal connections
			if (tokens[2].startsWith("10."))
				continue;

			String ip = tokens[2];
			DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
			java.util.Date date = df.parse(tokens[3].substring(0, tokens[3].indexOf("+")));

			addresses.putIfAbsent(ip, new IPAddress(ip));
			IPAddress address = addresses.get(ip);

			// Add this login attempt to the IP's hash of logins
			address.addLogin(date.getTime(), usePass);
		}

		in.close();
	}

	private void readSecure(String filename)
		throws Exception
	{
		BufferedReader in = new BufferedReader(new FileReader(filename));

		String str = null;
		while ((str = in.readLine()) != null)
		{
			if (str.isEmpty())
				continue;

			String[] tokens = str.split("\\s+");

			// Skip on internal connections
			if (tokens[10].startsWith("10.25."))
				continue;

			// /var/log/secure time format is just Apr 12 03:33:16 so we need
			// to insert the year for easy parsing
			String ip = tokens[10];
			String dateStr = Calendar.getInstance().get(Calendar.YEAR) + " "
				+ tokens[0] + " " + tokens[1] + " " + tokens[2];
			DateFormat df = new SimpleDateFormat("yyyy MMM dd HH:mm:ss");
			Calendar c = Calendar.getInstance();
			c.setTime(df.parse(dateStr));

			// Deal with year end wrap around - do YEAR-1 so, eg, Dec entries
			// (read in Jan) are set to the older year)
			if (c.getTimeInMillis() > NOW)
				c.set(Calendar.YEAR, c.get(Calendar.YEAR)-1);

//			System.out.println("  " + new Date(c.getTimeInMillis()));

			addresses.putIfAbsent(ip, new IPAddress(ip));
			IPAddress address = addresses.get(ip);

			// Add this login attempt to the IP's hash of logins
			address.addLogin(c.getTimeInMillis(), false);
		}

		in.close();
	}

	// Clear out entries older than a week
	private void clearOldEntries()
	{
		System.out.println("addresses.size() = " + addresses.size());

		// Iterates over the map, removing anything with old timestamps
		Iterator<Entry<String,IPAddress>> it = addresses.entrySet().iterator();
		while (it.hasNext())
		{
			Entry<String,IPAddress> entry = it.next();
			IPAddress address = entry.getValue();
			address.clearOldLogins();

//			if (address.lastFailTime < PERIOD && address.lastPassTime < PERIOD)
			if (address.logins.size() == 0)
				it.remove();
		}

		System.out.println("addresses.size() = " + addresses.size());
	}

	// Populates an IP address entry with gelocation data from geoplugin
	private void populateIPs()
	{
		for (IPAddress address: addresses.values())
		{
			if (address.lat != null && address.lon != null)
				continue;

			try
			{
				URL url = new URL("http://ip-api.com/xml/" + address.ip);

				HttpURLConnection conn = (HttpURLConnection) url.openConnection();

				DocumentBuilder db = DocumentBuilderFactory.newInstance().newDocumentBuilder();
				Document doc = db.parse(conn.getInputStream());

				NodeList nodes = doc.getElementsByTagName("*");

				for (int i = 0; i < nodes.getLength(); i++)
				{
					Node n = nodes.item(i);
					if (n.getTextContent().isEmpty())
						continue;

					if (n.getNodeName().toLowerCase().equals("lat"))
						address.lat = n.getTextContent();
					else if (n.getNodeName().toLowerCase().equals("lon"))
						address.lon = n.getTextContent();
					else if (n.getNodeName().toLowerCase().equals("city"))
						address.city = n.getTextContent();
					else if (n.getNodeName().toLowerCase().equals("country"))
						address.country = n.getTextContent();
				}

				System.out.println("Details for " + address.ip);
				System.out.println("  " + address.lat + "," + address.lon + "\t" + address.city + "\t" + address.country);

				// Wait 1500ms between requests
				try { Thread.sleep(1500); }
				catch (InterruptedException e) {}
			}
			catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	private void createMapFile()
		throws Exception
	{
		HashMap<String, Location> locations = new HashMap<>();

		for (IPAddress address: addresses.values())
		{
			if (address.lat == null || address.lon == null)
				continue;

			if (address.lat.isEmpty())
				System.out.println(address.ip);

			String key = address.lat + "," + address.lon;

			// Location will either be city,country or just country
			Location location = new Location(address.lat, address.lon);
			if (address.city != null && address.country != null)
				location.description = address.city + ", " + address.country;
			else if (address.country != null)
				location.description = address.country;

			// Do we have an entry for this location yet?
			locations.putIfAbsent(key, location);
			location = locations.get(key);
			location.ipCount++;
			location.pass += address.countPasses();
			location.fail += address.countFails();

			// Update the last connection times if this IP instance is newer
			location.lastPassTime = Math.max(location.lastPassTime, address.lastPass());
			location.lastFailTime = Math.max(location.lastFailTime, address.lastFail());
		}

		BufferedWriter out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream("map.xml"), "UTF-8"));
		out.write("<markers>");
		out.newLine();

		for (Location location: locations.values())
		{
			location.description = location.description.replace("&", "&amp;");
			out.write("  <marker lat=\"" + location.lat + "\" "
				+ "lng=\"" + location.lon + "\" "
				+ "title=\"" + location.getDescription() + "\" "
				+ "opacity=\"" + location.getOpacity() + "\" "
				+ "color=\"" + location.getColor() + "\"></marker>");
			out.newLine();
		}

		out.write("</markers>");
		out.newLine();
		out.close();
	}

	@SuppressWarnings("unchecked")
	private void loadDB()
		throws Exception
	{
		if (new File("logins.db").exists() == false)
			return;

		ObjectInputStream in = new ObjectInputStream(new FileInputStream("logins.db"));
		addresses = (HashMap<String,IPAddress>)in.readObject();
		in.close();
	}

	private void saveDB()
		throws Exception
	{
		ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream("logins.db"));
		out.writeObject(addresses);
		out.close();
	}

	private static class IPAddress implements Serializable
	{
		private String ip;
		private HashMap<Long, Boolean> logins = new HashMap<>();
		private String lat, lon, city, country;

		IPAddress(String ip)
		{
			this.ip = ip;
		}

		void addLogin(long time, boolean pass)
		{
			logins.put(time, pass);
		}

		void clearOldLogins()
		{
			Iterator<Entry<Long,Boolean>> it = logins.entrySet().iterator();

			// Remove login entries older than PERIOD
			while (it.hasNext())
				if (it.next().getKey() < PERIOD)
					it.remove();
		}

		int countPasses()
		{
			return Collections.frequency(logins.values(), true);
		}

		int countFails()
		{
			return Collections.frequency(logins.values(), false);
		}

		long lastPass()
		{
			long lastPass = 0;

			for (Long key: logins.keySet())
				if (logins.get(key) == true)
					lastPass = Math.max(lastPass, key);

			return lastPass;
		}

		long lastFail()
		{
			long lastFail = 0;

			for (Long key: logins.keySet())
				if (logins.get(key) == false)
					lastFail = Math.max(lastFail, key);

			return lastFail;
		}
	}

	// A location is obviously similar to an IP address, but it's not quite the
	// same thing; multiple IPs may map to one location, and we need to track
	// the most recent pass/fail times from all of them
	private static final class Location
	{
		private long lastPassTime, lastFailTime;
		private String lat, lon;
		private String description = "";

		// A count of how many IP addresses resolve to this location
		private int ipCount;
		// And counts of pass/fail
		private int pass, fail;

		Location(String lat, String lon)
		{
			this.lat = lat;
			this.lon = lon;
		}

		String getDescription()
		{
			return description + " (IPs: " + ipCount + ", Login attempts: " + pass + " good, " + fail + " bad)";
		}

		String getColor()
		{
			// "Green" if a login has worked at any point in time
			if (lastPassTime > 0)
				return "#017101";
			// Otherwise "red"
			else
				return "#e74c3c";
		}

		double getOpacity()
		{
			long time = Math.max(lastPassTime, lastFailTime);

			// Calculate an opacity, on a scale of 0 (oldest) to 1 (newest)
			long age = NOW - time;
			double ratio = 1 - (age / (double) (NOW-PERIOD));

			return ratio;
		}
	}
}