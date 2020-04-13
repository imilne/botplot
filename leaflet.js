function leafletInit() {
	var oldSchoolIcon = false;
	var map = L.map('worldmap').setView([38, 15], 2);

	L.tileLayer('//{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
		attribution: '&copy; <a href="//www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
		subdomains: ['a', 'b', 'c']
	}).addTo(map);
	
//	googleStreets = L.tileLayer('http://{s}.google.com/vt/lyrs=m&x={x}&y={y}&z={z}',{
//    maxZoom: 20,
//    subdomains:['mt0','mt1','mt2','mt3']
//});
//googleStreets.addTo(map);
	
//	googleTerrain = L.tileLayer('http://{s}.google.com/vt/lyrs=p&x={x}&y={y}&z={z}',{
//    maxZoom: 20,
//    subdomains:['mt0','mt1','mt2','mt3']
//});

//googleTerrain.addTo(map);

	var renderer = L.canvas();

	var xmlDoc = loadXml("map.xml");

	// Get all marker elements
	var items = xmlDoc.getElementsByTagName('marker');

	var marker;
	var markers = [];
	for(var i = 0; i < items.length; i++)
	{
		if(oldSchoolIcon) {
			// Extract location information
			marker = L.marker([items[i].getAttribute('lat'), items[i].getAttribute('lng')]).addTo(map).bindPopup(items[i].getAttribute('title'));
			markers.push(marker);
		} else {
			marker = L.circleMarker([items[i].getAttribute('lat'), items[i].getAttribute('lng')], {
				renderer: renderer,
				radius: 10,
				weight: 1,
				opacity: items[i].getAttribute('opacity'),
				fillOpacity: items[i].getAttribute('opacity'),
				color: items[i].getAttribute('color')
			}).addTo(map).bindTooltip(items[i].getAttribute('title'));
		}
	}

	if(oldSchoolIcon) {
		var group = new L.featureGroup(markers);
		map.fitBounds(group.getBounds().pad(0.5));
	}
}

/**
* Loads and returns the given xml file
*/
function loadXml(xmlUrl) 
{
	if (window.XMLHttpRequest)
	{
		// code for IE7+, Firefox, Chrome, Opera, Safari
		xmlhttp=new XMLHttpRequest();
	}
	else
	{
		// code for IE6, IE5
		xmlhttp=new ActiveXObject("Microsoft.XMLHTTP");
	}
	xmlhttp.open("GET", xmlUrl, false);
	xmlhttp.send();
	xmlDoc = xmlhttp.responseXML;
	return xmlDoc;
}