package com.locus.app.net;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns whatever the user types ("pizza", "dentist", "laptop repair", "mosque", "bkash agent" ...) into
 * OpenStreetMap (Overpass) filters. Pure Java, so it is unit-tested on the JVM.
 *
 * Three layers, all automatic:
 *  1. a synonym dictionary (medicine -> pharmacy, petrol -> fuel, parlour -> hairdresser ...),
 *  2. a generic match of the typed words against OSM tag VALUES (amenity=dentist, shop=bakery, cuisine=pizza ...),
 *  3. a generic match of the typed words against place NAMES / brands ("Pizza Hut", "Square Hospital").
 *
 * Because layers 2 and 3 are generic, words that are not in the dictionary still work.
 */
public final class QueryPlanner {

    private static final String KEYS =
            "^(amenity|shop|cuisine|craft|healthcare|leisure|tourism|office)$";

    private static final Set<String> STOP_WORDS = new LinkedHashSet<>(java.util.Arrays.asList(
            "near", "nearby", "nearest", "me", "my", "the", "for", "and", "best", "good", "cheap", "open",
            "shop", "store", "place", "places", "in", "at", "to", "a", "an", "of", "with", "around", "find",
            "need", "want", "where", "can", "get", "buy", "service", "services", "any", "some"));

    private static final class Entry {
        final Pattern[] triggers;
        final String[] filters;

        Entry(String triggers, String[] filters) {
            String[] t = triggers.split(",");
            this.triggers = new Pattern[t.length];
            for (int i = 0; i < t.length; i++) {
                // whole word, optional plural: "lab" must not fire for "label"
                this.triggers[i] = Pattern.compile("(?<![\\p{L}\\p{M}\\p{N}])" + Pattern.quote(t[i].trim())
                        + "(s|es)?(?![\\p{L}\\p{M}\\p{N}])");
            }
            this.filters = filters;
        }

        boolean matches(String text) {
            for (Pattern p : triggers) {
                if (p.matcher(text).find()) return true;
            }
            return false;
        }
    }

    // ---- filter builders -----------------------------------------------------------------

    /** ["key"~"^(a|b)$"] */
    private static String v(String key, String values) {
        return "[\"" + key + "\"~\"^(" + values + ")$\"]";
    }

    /** any of the common service keys has one of these values */
    private static String any(String values) {
        return "[~\"" + KEYS + "\"~\"^(" + values + ")$\",i]";
    }

    /** name / English name / brand contains the regex */
    private static String name(String regex) {
        return "[~\"^(name|name:en|brand)$\"~\"" + regex + "\",i]";
    }

    private static final List<Entry> DICT = new ArrayList<>();

    private static void e(String triggers, String... filters) {
        DICT.add(new Entry(triggers, filters));
    }

    static {
        e("food,eat,eating,restaurant,lunch,dinner,breakfast,hungry,meal,রেস্টুরেন্ট,খাবার,रेस्तरां,रेस्टोरेंट,खाना,भोजन",
                v("amenity", "restaurant|fast_food|food_court|cafe"));
        e("biryani,biriyani,biriani,kacchi,kachchi,tehari,polao", any("biryani|biriyani"),
                name("biryani|biriyani|biriani|kacchi|kachchi|tehari"));
        e("shawarma,kebab,roll,fuchka,fuska,chotpoti,snack,snacks,sandwich",
                v("amenity", "fast_food|restaurant|cafe"), name("shawarma|kebab|fuchka|fuska|chotpoti"));
        e("tea,coffee,cafe,chai,tea stall", v("amenity", "cafe"), v("shop", "tea|coffee"));
        e("cake,pastry,sweet,sweets,mithai,misti,bakery,bread,confectionery",
                v("shop", "bakery|pastry|confectionery"), name("sweet|misti|mistanno|confection"));
        e("ice cream,icecream,ice-cream,gelato,kulfi", v("amenity", "ice_cream"), v("shop", "ice_cream"),
                name("ice ?cream|kulfi"));

        e("hospital,emergency,ambulance,হাসপাতাল,अस्पताल,एम्बुलेंस", v("amenity", "hospital|clinic"),
                v("emergency", "ambulance_station"));
        e("doctor,physician,medical,clinic,chamber,specialist,ডাক্তার,ক্লিনিক,डॉक्टर,क्लिनिक",
                v("amenity", "doctors|clinic|hospital"), v("healthcare", "doctor|clinic|hospital|centre"));
        e("dentist,dental,teeth,tooth", v("amenity", "dentist"), v("healthcare", "dentist"), name("dental|dentist"));
        e("pharmacy,medicine,medicines,drug,drugs,chemist,ফার্মেসি,ওষুধ,फार्मेसी,दवा,दवाई,मेडिकल",
                v("amenity", "pharmacy"), v("healthcare", "pharmacy"), name("pharmacy|medicine|chemist"));
        e("lab,test,diagnostic,pathology,xray,x-ray,ultrasound,scan,mri",
                v("healthcare", "laboratory|diagnostic|centre"), v("amenity", "clinic"),
                name("diagnostic|laborator|pathology|x-?ray|ultrasound"));
        e("blood,donor,donation", v("healthcare", "blood_donation|blood_bank"), name("blood"));

        e("atm,cash,withdraw,money,এটিএম,एटीएम", v("amenity", "atm|bank|bureau_de_change"));
        e("bank,loan,ব্যাংক,बैंक", v("amenity", "bank"));
        e("grocery,groceries,supermarket,supershop,super shop,bazar,bazaar,market,mart,convenience,daily needs,বাজার",
                v("shop", "supermarket|convenience|greengrocer|department_store|mall|general"),
                v("amenity", "marketplace"));
        e("vegetable,vegetables,fruit,fruits,fish,meat", v("shop", "greengrocer|butcher|seafood|farm"),
                v("amenity", "marketplace"));

        e("mosque,masjid,namaz,prayer,মসজিদ,मस्जिद", "[\"amenity\"=\"place_of_worship\"][\"religion\"=\"muslim\"]",
                name("mosque|masjid|jame"));
        e("temple,mandir,puja,মন্দির,मंदिर", "[\"amenity\"=\"place_of_worship\"][\"religion\"~\"hindu|buddhist\"]");
        e("church", "[\"amenity\"=\"place_of_worship\"][\"religion\"=\"christian\"]");

        e("fuel,petrol,diesel,octane,gas,cng,pump,পেট্রোল,पेट्रोल", v("amenity", "fuel"));
        e("hotel,stay,room,lodging,guest house,hostel,resort,হোটেল,होटल",
                v("tourism", "hotel|guest_house|hostel|motel|apartment|resort"));
        e("school,college,university,coaching,tuition,institute,academy,স্কুল,কলেজ,স্কুল,स्कूल,कॉलेज,विश्वविद्यालय",
                v("amenity", "school|college|university|kindergarten|language_school|music_school"),
                name("coaching|tuition|academy"));
        e("library,book,books,stationery,pen", v("amenity", "library"), v("shop", "books|stationery"));

        e("barber,salon,hair,haircut,parlour,parlor,beauty,spa,makeup,সেলুন,नाई,सैलून",
                v("shop", "hairdresser|beauty|massage|cosmetics"), v("amenity", "spa"));
        e("tailor,tailoring,boutique,clothes,clothing,dress,fashion,saree,shirt,panjabi,fabric,cloth",
                v("shop", "tailor|clothes|boutique|fabric|fashion"), v("craft", "tailor"));
        e("shoe,shoes,sandal,footwear", v("shop", "shoes"));
        e("jewellery,jewelry,gold,ornament", v("shop", "jewelry"));
        e("toy,toys,gift,gifts,flower,flowers,florist", v("shop", "toys|gift|florist"));
        e("furniture,sofa,bed,mattress,interior", v("shop", "furniture|interior_decoration|bed|kitchen"));

        e("mobile,phone,smartphone,sim,recharge", v("shop", "mobile_phone|telecommunication|electronics"),
                name("mobile|phone"));
        e("laptop,computer,pc,printer,ink", v("shop", "computer|electronics"), v("craft", "electronics_repair"),
                name("computer|laptop"));
        e("electronics,tv,fridge,refrigerator,ac,appliance,appliances,fan", v("shop", "electronics|appliance|electrical"));
        e("repair,mechanic,fix", v("craft", "electronics_repair|shoemaker|watchmaker|carpenter|electrician|plumber"),
                v("shop", "car_repair|motorcycle_repair|repair"));
        e("electrician,wiring,electric", v("craft", "electrician"), v("shop", "electrical"));
        e("plumber,plumbing,pipe,sanitary", v("craft", "plumber"),
                v("shop", "plumbing|bathroom_furnishing|doityourself"));
        e("hardware,tools,paint,cement,rod,brick,construction",
                v("shop", "hardware|doityourself|paint|building_materials|trade"));
        e("car,garage,tyre,tire,workshop,carwash,car wash", v("shop", "car_repair|car|tyres|car_parts"),
                v("amenity", "car_wash|car_rental"));
        e("bike,bicycle,cycle,motorcycle,motorbike", v("shop", "bicycle|motorcycle|motorcycle_repair"));

        e("gym,fitness,workout,exercise,yoga,sport,sports,stadium,ground,playground,football,cricket",
                v("leisure", "fitness_centre|sports_centre|pitch|stadium|playground|swimming_pool"));
        e("park,garden,lake,picnic,পার্ক,पार्क", v("leisure", "park|garden|playground|nature_reserve"));
        e("bus,stand,terminal,station,transport,launch,ghat,বাস,बस", v("amenity", "bus_station|ferry_terminal"),
                v("railway", "station|halt"), v("public_transport", "station"));
        e("train,rail,railway", v("railway", "station|halt"));

        e("police,thana,পুলিশ,থানা,पुलिस,थाना", v("amenity", "police"));
        e("fire,firefighter", v("amenity", "fire_station"));
        e("post,post office,courier,parcel,delivery", v("amenity", "post_office|post_box"),
                name("courier|parcel|sundarban|sa paribahan|redx|pathao"));
        e("laundry,dry clean,drycleaning,ironing,washing", v("shop", "laundry|dry_cleaning"), v("amenity", "laundry"));
        e("photocopy,xerox,print,printing,photo,studio", v("shop", "copyshop|photo|printing"),
                v("craft", "printer"), name("print|xerox|photocopy|studio"));
        e("cinema,movie,theatre,theater", v("amenity", "cinema|theatre"));
        e("toilet,washroom,restroom,wc,টয়লেট,शौचालय", v("amenity", "toilets"));
        e("water,drinking", v("amenity", "drinking_water"));
        e("parking,পার্কিং,पार्किंग", v("amenity", "parking"));
        e("charging,ev", v("amenity", "charging_station"));
        e("vet,veterinary,pet,pets,animal", v("amenity", "veterinary"), v("shop", "pet"));
        e("internet,cyber,wifi,broadband,isp", v("amenity", "internet_cafe"), name("internet|broadband|cable"));
        e("bkash,nagad,rocket,mfs,agent", name("bkash|nagad|rocket|upay|agent"));
        e("government,office,union,ward,municipality,court,passport,nid,registry,tax",
                v("amenity", "townhall|courthouse|public_building|post_office"),
                v("office", "government|administrative|tax_advisor|notary"));
        e("insurance,lawyer,law,advocate,notary,accountant", v("office", "insurance|lawyer|accountant|notary"));
    }

    private QueryPlanner() {
    }

    // ---- public API ----------------------------------------------------------------------

    /**
     * Lower-case, trimmed, max 40 characters, and stripped of everything that could break an Overpass
     * query (quotes, brackets, backslashes, regex operators ...). Letters of any script are kept.
     */
    public static String normalize(String raw) {
        if (raw == null) return "";
        String s = raw.toLowerCase(Locale.ROOT).trim();
        // \p{M} keeps combining marks - Bangla/Hindi vowel signs are marks, not letters
        s = s.replaceAll("[^\\p{L}\\p{M}\\p{N} \\-']", " ");
        s = s.replaceAll("\\s+", " ").trim();
        if (s.length() > 40) s = s.substring(0, 40).trim();
        return s;
    }

    /** Words of the query that carry meaning (stop words such as "near me" removed, max 3). */
    public static List<String> terms(String norm) {
        List<String> out = new ArrayList<>();
        List<String> all = new ArrayList<>();
        for (String w : norm.split(" ")) {
            if (w.length() >= 3) all.add(w);
            if (w.length() >= 3 && !STOP_WORDS.contains(w)) out.add(w);
        }
        if (out.isEmpty()) out = all;
        return out.size() > 3 ? new ArrayList<>(out.subList(0, 3)) : out;
    }

    /** All Overpass tag filters (each one is a complete "[...]" suffix for an nwr statement). */
    public static List<String> filtersFor(String norm) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Entry en : DICT) {
            if (en.matches(norm)) Collections.addAll(out, en.filters);
        }
        List<String> phrases = new ArrayList<>();
        if (norm.length() >= 2) phrases.add(norm);
        for (String t : terms(norm)) {
            if (!phrases.contains(t)) phrases.add(t);
        }
        for (String p : phrases) {
            String rx = p.replace(" ", "[ _-]");
            out.add("[~\"" + KEYS + "\"~\"(^|;)" + rx + "s?(;|$)\",i]");
            if (p.length() >= 3) out.add(name(p));
        }
        return new ArrayList<>(out);
    }

    /** "fast_food" -> "Fast food" */
    public static String humanize(String value) {
        if (value == null || value.isEmpty()) return "";
        String s = value.replace('_', ' ').replace(';', ',').trim();
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    /** Short readable type for a place, from its OSM tags: "Pizza restaurant", "Pharmacy", "Bakery" ... */
    public static String labelFor(Map<String, String> tags) {
        String amenity = tags.get("amenity");
        String cuisine = tags.get("cuisine");
        if (cuisine != null && !cuisine.isEmpty() && amenity != null
                && (amenity.equals("restaurant") || amenity.equals("fast_food") || amenity.equals("cafe"))) {
            String first = cuisine.split(";")[0];
            return humanize(first) + " " + amenity.replace('_', ' ');
        }
        String[] keys = {"amenity", "shop", "healthcare", "craft", "tourism", "leisure", "office",
                "railway", "emergency"};
        for (String k : keys) {
            String val = tags.get(k);
            if (val != null && !val.isEmpty() && !val.equals("yes")) return humanize(val);
        }
        return "Place";
    }
}
