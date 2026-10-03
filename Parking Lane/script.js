// ==========================================
// SMART PARKING SYSTEM (connected to MySQL via backend API)
// ==========================================

// ------------------------------------------
// BACKEND ADDRESS
// Local (localhost)  -> same server, nothing to change
// Netlify / online   -> your Java server's address (no slash at the end)
// ------------------------------------------
const BACKEND_URL = "https://YOUR-BACKEND-URL.onrender.com";

const API_BASE = (location.hostname === "localhost" || location.hostname === "127.0.0.1")
    ? ""
    : BACKEND_URL;

let selectedSlot = null;
let selectedSlotType = null;

// In-memory cache of the last slot data fetched from the server
let parkingData = { CAR: [], BIKE: [], ACCESSIBLE: [] };


// ------------------------------------------
// TOAST NOTIFICATIONS (replaces alert() popups)
// ------------------------------------------

function showToast(message, type = "success") {
    const container = document.getElementById("toastContainer");

    if (!container) {
        alert(message);
        return;
    }

    const toast = document.createElement("div");
    toast.className = "toast " + type;
    toast.textContent = message;

    container.appendChild(toast);

    setTimeout(() => {
        toast.remove();
    }, 3000);
}


// ------------------------------------------
// SESSION HELPERS (who's currently logged in)
// ------------------------------------------

function getCurrentUser() {
    const raw = localStorage.getItem("currentUser");
    return raw ? JSON.parse(raw) : null;
}

function setCurrentUser(user) {
    localStorage.setItem("currentUser", JSON.stringify(user));
}

function clearCurrentUser() {
    localStorage.removeItem("currentUser");
}

// Headers for requests that need a logged-in user
function authHeaders() {
    const user = getCurrentUser();
    const headers = { "Content-Type": "application/json" };
    if (user && user.token) headers["Authorization"] = "Bearer " + user.token;
    return headers;
}

// Called when the server says our login has expired (e.g. server restarted)
function handleAuthExpired() {
    clearCurrentUser();
    updateLoginUI();
    showToast("Your session expired. Please login again.", "error");
    showLogin();
    displaySlots();
}

// Stops user-typed text from being treated as HTML
function escapeHtml(value) {
    return String(value == null ? "" : value)
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#39;");
}


// ------------------------------------------
// LOAD + DISPLAY SLOTS (from server)
// ------------------------------------------

let slotErrorShown = false;

async function loadSlots() {
    try {
        const res = await fetch(API_BASE + "/api/slots");
        const data = await res.json();

        if (!res.ok || !data.CAR) {
            throw new Error(data.message || "Server error");
        }

        parkingData = data;
        slotErrorShown = false;

    } catch (err) {
        console.error("Failed to load slots:", err);
        parkingData = { CAR: [], BIKE: [], ACCESSIBLE: [] };

        if (!slotErrorShown) {
            showToast("Could not load parking slots. Check the server window for the database error.", "error");
            slotErrorShown = true;
        }
    }
}

async function displaySlots() {

    await loadSlots();

    displaySlotType(parkingData.CAR, "carSlots", "CAR");
    displaySlotType(parkingData.BIKE, "bikeSlots", "BIKE");
    displaySlotType(parkingData.ACCESSIBLE, "accessibleSlots", "ACCESSIBLE");

    updateSummary();

    await displayCurrentBooking();
}


// ------------------------------------------
// CREATE SLOT BUTTONS
// ------------------------------------------

const SLOT_ICONS = { CAR: "🚗", BIKE: "🏍️", ACCESSIBLE: "♿" };

function displaySlotType(slots, containerId, type) {

    const container = document.getElementById(containerId);
    container.innerHTML = "";

    if (slots.length === 0) {
        container.innerHTML = '<p class="empty-slots">Slots could not be loaded right now.</p>';
        return;
    }

    slots.forEach(slot => {

        const button = document.createElement("button");
        button.classList.add("parking-slot");

        const available = slot.status === "AVAILABLE";
        button.classList.add(available ? "available" : "reserved");

        button.innerHTML =
            `<span class="slot-icon">${SLOT_ICONS[type]}</span>` +
            `<span class="slot-number">${slot.slotNumber}</span>` +
            `<span class="slot-status">${available ? "Available" : "Reserved"}</span>`;

        if (available) {
            button.onclick = function () {
                selectSlot(slot.slotNumber, getSlotType(slot.slotNumber));
            };
        } else {
            button.disabled = true;
        }

        container.appendChild(button);
    });
}


// ------------------------------------------
// FIND SLOT TYPE
// ------------------------------------------

function getSlotType(slotNumber) {
    if (slotNumber.startsWith("C")) return "CAR";
    if (slotNumber.startsWith("B")) return "BIKE";
    return "ACCESSIBLE";
}


// ------------------------------------------
// VEHICLE TYPE DROPDOWN (only show options
// relevant to the slot type that was clicked)
// ------------------------------------------

const VEHICLES = {
    CAR:    { label: "Car",    icon: "🚗" },
    BIKE:   { label: "Bike",   icon: "🏍️" },
    SCOOTY: { label: "Scooty", icon: "🛵" }
};

function renderVehicleChoice(containerId, hiddenId, types) {

    const box = document.getElementById(containerId);
    const hidden = document.getElementById(hiddenId);
    box.innerHTML = "";
    hidden.value = "";

    types.forEach(t => {
        const btn = document.createElement("button");
        btn.type = "button";
        btn.className = "vehicle-option";
        btn.dataset.value = t;
        btn.innerHTML = `<span class="v-icon">${VEHICLES[t].icon}</span><span>${VEHICLES[t].label}</span>`;
        btn.onclick = function () {
            hidden.value = t;
            box.querySelectorAll(".vehicle-option").forEach(b => b.classList.remove("selected"));
            btn.classList.add("selected");
        };
        box.appendChild(btn);
    });

    // Only one choice (e.g. car slot) -> pre-select it
    if (types.length === 1) box.firstChild.click();
}

function populateVehicleTypeOptions(slotType) {
    renderVehicleChoice("vehicleChoice", "vehicleType",
        slotType === "CAR" ? ["CAR"] : ["BIKE", "SCOOTY"]);
}


// ------------------------------------------
// SELECT SLOT
// ------------------------------------------

function selectSlot(slotNumber, slotType) {

    const currentUser = getCurrentUser();

    if (!currentUser) {
        showToast("Please login first.", "error");
        showLogin();
        return;
    }

    selectedSlot = slotNumber;
    selectedSlotType = slotType;

    if (slotType === "ACCESSIBLE") {
        document.getElementById("disabilityType").value = "";
        document.getElementById("accVehicleNumber").value = "";
        renderVehicleChoice("accVehicleChoice", "accVehicleType", ["CAR", "BIKE", "SCOOTY"]);
        document.getElementById("disabilityModal").style.display = "flex";
    } else {
        populateVehicleTypeOptions(slotType);
        document.getElementById("vehicleNumber").value = "";
        document.getElementById("selectedSlotText").innerText = "Selected Slot: " + slotNumber;
        document.getElementById("vehicleModal").style.display = "flex";
    }
}


// ------------------------------------------
// NORMAL BOOKING
// ------------------------------------------

async function confirmNormalBooking() {

    const vehicleNumber = document.getElementById("vehicleNumber").value.trim().toUpperCase();
    const vehicleType = document.getElementById("vehicleType").value;

    if (!vehicleNumber) {
        showToast("Please enter vehicle number.", "error");
        return;
    }

    if (!vehicleType) {
        showToast("Please select vehicle type.", "error");
        return;
    }

    // Safety net (dropdown is already filtered, but just in case)
    if (selectedSlotType === "CAR" && vehicleType !== "CAR") {
        showToast("This slot is only for cars.", "error");
        return;
    }

    if (selectedSlotType === "BIKE" && vehicleType !== "BIKE" && vehicleType !== "SCOOTY") {
        showToast("This slot is only for bikes and scooties.", "error");
        return;
    }

    const currentUser = getCurrentUser();

    await bookSlot(selectedSlot, selectedSlotType, vehicleNumber, vehicleType, null, currentUser);
}


// ------------------------------------------
// ACCESSIBLE BOOKING
// ------------------------------------------

async function confirmAccessibleBooking() {

    const disabilityType = document.getElementById("disabilityType").value;

    if (!disabilityType) {
        showToast("Please select your disability type.", "error");
        return;
    }

    const vehicleNumber = document.getElementById("accVehicleNumber").value.trim();
    if (!vehicleNumber) {
        showToast("Please enter vehicle number.", "error");
        return;
    }

    const type = document.getElementById("accVehicleType").value;
    if (!type) {
        showToast("Please select vehicle type.", "error");
        return;
    }

    const currentUser = getCurrentUser();

    await bookSlot(selectedSlot, "ACCESSIBLE", vehicleNumber.toUpperCase(), type, disabilityType, currentUser);
}


// ------------------------------------------
// BOOK SLOT (calls backend, which writes to MySQL)
// ------------------------------------------

async function bookSlot(slotNumber, slotType, vehicleNumber, vehicleType, disabilityType, currentUser) {

    try {
        const res = await fetch(API_BASE + "/api/book", {
            method: "POST",
            headers: authHeaders(),
            body: JSON.stringify({
                slotNumber: slotNumber,
                slotType: slotType,
                vehicleNumber: vehicleNumber,
                vehicleType: vehicleType,
                disabilityType: disabilityType,
                email: currentUser.email,
                userName: currentUser.name
            })
        });

        const data = await res.json();

        if (res.status === 401) {
            handleAuthExpired();
            return;
        }

        if (!data.success) {
            showToast(data.message || "Could not book this slot.", "error");
            await displaySlots();
            return;
        }

        closeAllModals();

        showToast("Slot " + slotNumber + " booked for 2 hours!", "success");

        await displaySlots();

    } catch (err) {
        console.error(err);
        showToast("Something went wrong while booking. Please try again.", "error");
    }
}


// ------------------------------------------
// SUMMARY
// ------------------------------------------

function updateSummary() {

    const carAvailable = parkingData.CAR.filter(s => s.status === "AVAILABLE").length;
    const bikeAvailable = parkingData.BIKE.filter(s => s.status === "AVAILABLE").length;
    const accessibleAvailable = parkingData.ACCESSIBLE.filter(s => s.status === "AVAILABLE").length;

    document.getElementById("carAvailable").innerText = carAvailable;
    document.getElementById("bikeAvailable").innerText = bikeAvailable;
    document.getElementById("accessibleAvailable").innerText = accessibleAvailable;

    document.getElementById("tabCountCAR").innerText = carAvailable;
    document.getElementById("tabCountBIKE").innerText = bikeAvailable;
    document.getElementById("tabCountACCESSIBLE").innerText = accessibleAvailable;
}


// ------------------------------------------
// CURRENT BOOKING (fetched from server)
// ------------------------------------------

async function displayCurrentBooking() {

    const currentUser = getCurrentUser();
    const container = document.getElementById("bookingDetails");

    if (!currentUser) {
        container.innerHTML = "<p>Please login to see your booking.</p>";
        return;
    }

    let booking;
    try {
        const res = await fetch(API_BASE + "/api/mybooking", { headers: authHeaders() });

        if (res.status === 401) {
            // Stale login (e.g. server restarted) - quietly log out
            clearCurrentUser();
            updateLoginUI();
            container.innerHTML = "<p>Please login to see your booking.</p>";
            return;
        }

        booking = await res.json();
    } catch (err) {
        console.error(err);
        container.innerHTML = "<p>Could not load your booking.</p>";
        return;
    }

    if (!booking.exists) {
        container.innerHTML = "<p>You don't have an active booking.</p>";
        return;
    }

    const expiry = new Date(booking.expiryTime);

    container.innerHTML = `
        <div class="booking-card">
            <h3>Slot ${escapeHtml(booking.slotNumber)}</h3>
            <p><strong>Vehicle:</strong> ${escapeHtml(booking.vehicleNumber)}</p>
            <p><strong>Vehicle Type:</strong> ${escapeHtml(booking.vehicleType)}</p>
            ${
                booking.disabilityType
                ? `<p><strong>Accessibility:</strong> ${escapeHtml(booking.disabilityType)}</p>`
                : ""
            }
            <p><strong>Expires:</strong> ${expiry.toLocaleString()}</p>
            <button class="cancel-button" onclick="cancelBooking('${escapeHtml(booking.slotNumber)}')">
                Cancel Booking
            </button>
        </div>
    `;
}


// ------------------------------------------
// CANCEL BOOKING
// ------------------------------------------

async function cancelBooking(slotNumber) {

    const confirmCancel = confirm("Are you sure you want to cancel this booking?");
    if (!confirmCancel) return;

    const currentUser = getCurrentUser();

    try {
        const res = await fetch(API_BASE + "/api/cancel", {
            method: "POST",
            headers: authHeaders(),
            body: JSON.stringify({
                slotNumber: slotNumber,
                email: currentUser.email
            })
        });

        const data = await res.json();

        if (res.status === 401) {
            handleAuthExpired();
            return;
        }

        if (!data.success) {
            showToast(data.message || "Could not cancel this booking.", "error");
            return;
        }

        showToast("Your booking has been cancelled.", "success");
        await displaySlots();

    } catch (err) {
        console.error(err);
        showToast("Something went wrong while cancelling. Please try again.", "error");
    }
}


// ------------------------------------------
// REGISTER (calls backend, which writes to MySQL)
// ------------------------------------------

async function register() {

    const name = document.getElementById("registerName").value.trim();
    const email = document.getElementById("registerEmail").value.trim();
    const password = document.getElementById("registerPassword").value;

    if (!name || !email || !password) {
        showToast("Please fill all fields.", "error");
        return;
    }

    try {
        const res = await fetch(API_BASE + "/api/register", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ name, email, password })
        });

        const data = await res.json();

        if (!data.success) {
            showToast(data.message || "Registration failed.", "error");
            return;
        }

        showToast("Registration successful! Please login.", "success");
        closeModal();
        showLogin();

    } catch (err) {
        console.error(err);
        showToast("Something went wrong while registering. Please try again.", "error");
    }
}


// ------------------------------------------
// LOGIN (calls backend, which checks MySQL)
// ------------------------------------------

async function login() {

    const email = document.getElementById("loginEmail").value.trim();
    const password = document.getElementById("loginPassword").value;

    try {
        const res = await fetch(API_BASE + "/api/login", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ email, password })
        });

        const data = await res.json();

        if (!data.success) {
            showToast(data.message || "Invalid email or password.", "error");
            return;
        }

        setCurrentUser({ name: data.name, email: data.email, token: data.token });

        showToast("Welcome " + data.name + "!", "success");

        closeModal();

        updateLoginUI();
        await displaySlots();

    } catch (err) {
        console.error(err);
        showToast("Something went wrong while logging in. Please try again.", "error");
    }
}


// ------------------------------------------
// LOGOUT
// ------------------------------------------

async function logout() {
    clearCurrentUser();
    updateLoginUI();
    await displaySlots();
    showToast("You have been logged out.", "info");
}


// ------------------------------------------
// LOGIN UI
// ------------------------------------------

function updateLoginUI() {

    const currentUser = getCurrentUser();

    const loginButton = document.getElementById("loginNavBtn");
    const registerButton = document.getElementById("registerNavBtn");
    const logoutButton = document.getElementById("logoutBtn");
    const message = document.getElementById("parkingMessage");

    if (currentUser) {
        loginButton.style.display = "none";
        registerButton.style.display = "none";
        logoutButton.style.display = "inline-block";
        message.innerText = "Welcome " + currentUser.name + "! Select an available slot.";
    } else {
        loginButton.style.display = "inline-block";
        registerButton.style.display = "inline-block";
        logoutButton.style.display = "none";
        message.innerText = "Login to book a parking slot.";
    }
}


// ------------------------------------------
// MODALS
// ------------------------------------------

function showLogin() {
    document.getElementById("registerModal").style.display = "none";
    document.getElementById("loginModal").style.display = "flex";
}

function showRegister() {
    document.getElementById("loginModal").style.display = "none";
    document.getElementById("registerModal").style.display = "flex";
}

function closeModal() {
    document.getElementById("loginModal").style.display = "none";
    document.getElementById("registerModal").style.display = "none";
}

function closeDisabilityModal() {
    document.getElementById("disabilityModal").style.display = "none";
}

function closeVehicleModal() {
    document.getElementById("vehicleModal").style.display = "none";
}

function closeAllModals() {
    closeModal();
    closeDisabilityModal();
    closeVehicleModal();
}

function switchToRegister() {
    closeModal();
    showRegister();
}

function switchToLogin() {
    closeModal();
    showLogin();
}


// ------------------------------------------
// OPEN PARKING
// ------------------------------------------

function openParking() {
    location.hash = "parking";

    const currentUser = getCurrentUser();
    if (!currentUser) {
        setTimeout(showLogin, 400);
    }
}


// ------------------------------------------
// PAGE NAVIGATION (one section visible at a time)
// ------------------------------------------

const PAGES = ["home", "parking", "about"];

function showPage(name) {

    if (!PAGES.includes(name)) name = "home";

    PAGES.forEach(p => {
        document.getElementById("page-" + p).classList.toggle("active", p === name);
    });

    document.querySelectorAll(".nav-links a[data-page]").forEach(a => {
        a.classList.toggle("active", a.dataset.page === name);
    });

    window.scrollTo(0, 0);
}

window.addEventListener("hashchange", () => showPage(location.hash.slice(1)));


// ------------------------------------------
// SLOT TABS (Car / Bike & Scooty / Accessible)
// ------------------------------------------

function showSlotTab(type) {
    document.querySelectorAll(".slot-tab").forEach(t => {
        t.classList.toggle("active", t.dataset.type === type);
    });
    document.querySelectorAll(".slot-section").forEach(s => {
        s.classList.toggle("active", s.id === "section-" + type);
    });
}


// ------------------------------------------
// START APPLICATION
// ------------------------------------------

showPage(location.hash.slice(1));
updateLoginUI();
displaySlots();

// Refresh every 30 seconds (picks up expired bookings, other users' bookings)
setInterval(displaySlots, 30000);
