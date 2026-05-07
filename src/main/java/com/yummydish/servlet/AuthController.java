package com.yummydish.servlet;

import com.yummydish.model.*;
import com.yummydish.service.*;
import com.yummydish.util.FileStorageUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

// ── Global model attributes injected into every JSP ──────────────
@org.springframework.web.bind.annotation.ControllerAdvice

class GlobalAdvice {
    @Value("${google.maps.api.key:}")    private String mapsKey;
    @Value("${firebase.api.key:}")       private String fbKey;
    @Value("${firebase.auth.domain:}")   private String fbDomain;
    @Value("${firebase.project.id:}")    private String fbProject;
    @Value("${google.oauth.client.id:}") private String googleClientId;
    @Value("${google.oauth.client.id:}") private String googleOAuthClientId;

    private final OfferService offerService;
    @Autowired GlobalAdvice(OfferService offerService) { this.offerService = offerService; }

    @ModelAttribute("googleMapsApiKey")      public String mapsKey()          { return mapsKey; }
    @ModelAttribute("googleOAuthClientId")   public String googleOAuthClientId() { return googleOAuthClientId; }
    @ModelAttribute("firebaseApiKey")     public String fbKey()     { return fbKey; }
    @ModelAttribute("firebaseAuthDomain") public String fbDomain()  { return fbDomain; }
    @ModelAttribute("firebaseProjectId")  public String fbProject() { return fbProject; }
    @ModelAttribute("activeOffers")       public List<Offer> offers(){ return offerService.getActive(); }
}

// ═══════════════════════════════════════════════════════════════════
// CUSTOMER AUTH — /login, /signup, /logout, /forgot-password
// ═══════════════════════════════════════════════════════════════════
@Controller
class AuthController {
    private final UserService userService;
    @Autowired AuthController(UserService us) { this.userService = us; }

    @GetMapping("/")
    public String home(HttpSession s, Model m) {
        // If logged in, show home landing; if not, show home landing (public page)
        m.addAttribute("user", s.getAttribute("user"));
        return "index";
    }

    @GetMapping("/login")
    public String loginPage(HttpSession s) {
        return s.getAttribute("user") != null ? "redirect:/menu" : "auth/login";
    }

    @PostMapping("/login")
    public String doLogin(@RequestParam String email,
                          @RequestParam String password,
                          @RequestParam(defaultValue = "false") boolean stayLoggedIn,
                          HttpSession s, Model m) {
        User u = userService.authenticate(email, password);
        if (u == null) {
            m.addAttribute("error", "Invalid email or password.");
            return "auth/login";
        }
        // Block admins and drivers from customer login page
        if ("ADMIN".equals(u.getRole()) || "DRIVER".equals(u.getRole())) {
            m.addAttribute("error", "Please use the Admin / Driver login page.");
            return "auth/login";
        }
        s.setAttribute("user", u);
        if (stayLoggedIn) s.setMaxInactiveInterval(2592000);
        return "redirect:/menu";
    }

    @GetMapping("/signup")
    public String signupPage(HttpSession s) {
        return s.getAttribute("user") != null ? "redirect:/menu" : "auth/signup";
    }

    @PostMapping("/signup")
    public String doSignup(@RequestParam String name,
                           @RequestParam String email,
                           @RequestParam String password,
                           @RequestParam(defaultValue = "") String phone,
                           @RequestParam(defaultValue = "") String address,
                           @RequestParam(required = false, defaultValue = "") String cardNumber,
                           @RequestParam(required = false, defaultValue = "") String cardHolder,
                           @RequestParam(required = false, defaultValue = "") String cardExpiry,
                           HttpSession s, Model m) {
        try {
            User u = userService.register(name, email, password, phone, address,
                                          cardNumber, cardHolder, cardExpiry);
            s.setAttribute("user", u);
            return "redirect:/menu";
        } catch (IllegalArgumentException e) {
            m.addAttribute("error", e.getMessage());
            return "auth/signup";
        } catch (IOException e) {
            m.addAttribute("error", "Registration failed. Please try again.");
            return "auth/signup";
        }
    }

    @PostMapping("/social-login")
    public String socialLogin(@RequestParam(defaultValue = "") String name,
                              @RequestParam(defaultValue = "") String email,
                              @RequestParam(defaultValue = "") String pic,
                              HttpSession s) throws IOException {
        if (email.isBlank()) return "redirect:/login";
        User existing = userService.findByEmail(email);
        if (existing != null) {
            if ("ADMIN".equals(existing.getRole()) || "DRIVER".equals(existing.getRole()))
                return "redirect:/login?error=staff";
            if (!pic.isBlank()) userService.updateProfilePic(existing.getId(), pic);
            s.setAttribute("user", userService.findById(existing.getId()));
            return "redirect:/menu";
        }
        String autoPass = "SOCIAL_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        User nu = userService.register(name.isBlank() ? email.split("@")[0] : name,
                                       email, autoPass, "", "Please update your address",
                                       "", "", "");
        if (!pic.isBlank()) userService.updateProfilePic(nu.getId(), pic);
        s.setAttribute("user", nu);
        return "redirect:/menu";
    }

    @GetMapping("/forgot-password") public String forgotPage() { return "auth/forgot"; }
    @PostMapping("/forgot-password")
    public String doForgot(@RequestParam String email, Model m) {
        User u = userService.findByEmail(email);
        if (u != null) m.addAttribute("success", "Reset link sent to " + email + " (Demo: password unchanged)");
        else           m.addAttribute("error",   "No account found with that email.");
        return "auth/forgot";
    }

    @GetMapping("/logout")
    public String logout(HttpSession s) { s.invalidate(); return "redirect:/login"; }
}

// ═══════════════════════════════════════════════════════════════════
// ADMIN AUTH — /admin/login (separate page, separate session key)
// ═══════════════════════════════════════════════════════════════════
@Controller @RequestMapping("/account")
class AccountController {
    private final UserService userService;
    @Autowired AccountController(UserService us) { this.userService = us; }

    private boolean isCustomer(HttpSession s) {
        Object u = s.getAttribute("user");
        return u instanceof User && "CUSTOMER".equals(((User)u).getRole());
    }

    @GetMapping public String profile(HttpSession s, Model m) {
        if (!isCustomer(s)) {
            // Drivers go to driver portal, admins to admin portal
            if (s.getAttribute("driver") != null) return "redirect:/driver/dashboard";
            if (s.getAttribute("admin") != null)  return "redirect:/admin/dashboard";
            return "redirect:/login";
        }
        // Refresh user from file so loyalty points are up to date
        User u = (User) s.getAttribute("user");
        User fresh = userService.findById(u.getId());
        if (fresh != null) s.setAttribute("user", fresh);
        m.addAttribute("user", s.getAttribute("user"));
        return "account/profile";
    }

    @PostMapping("/update")
    public String update(@RequestParam(defaultValue = "") String name,
                         @RequestParam(defaultValue = "") String phone,
                         @RequestParam(defaultValue = "") String address,
                         @RequestParam(required = false, defaultValue = "") String cardNumber,
                         @RequestParam(required = false, defaultValue = "") String cardHolder,
                         @RequestParam(required = false, defaultValue = "") String cardExpiry,
                         HttpSession s, Model m) throws IOException {
        User u = (User) s.getAttribute("user"); if (u == null) return "redirect:/login";
        userService.update(u.getId(), name, phone, address, cardNumber, cardHolder, cardExpiry);
        User updated = userService.findById(u.getId());
        s.setAttribute("user", updated != null ? updated : u);
        m.addAttribute("user", s.getAttribute("user"));
        m.addAttribute("success", "Profile updated! ✅");
        return "account/profile";
    }

    @PostMapping("/delete")
    public String delete(HttpSession s) throws IOException {
        User u = (User) s.getAttribute("user");
        if (u != null) { userService.delete(u.getId()); s.invalidate(); }
        return "redirect:/login?deleted=true";
    }

    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(@RequestBody Map<String, String> body, HttpSession s) {
        User u = (User) s.getAttribute("user");
        if (u == null) return ResponseEntity.status(401).body(Map.of("error", "Not logged in"));
        String current = body.getOrDefault("currentPassword", "");
        String newPwd  = body.getOrDefault("newPassword", "");
        if (current.isBlank() || newPwd.isBlank() || newPwd.length() < 6) {
            return ResponseEntity.badRequest().body(Map.of("error", "Password must be at least 6 characters."));
        }
        try {
            boolean ok = userService.changePassword(u.getId(), current, newPwd);
            if (!ok) return ResponseEntity.badRequest().body(Map.of("error", "Current password is incorrect."));
            User updated = userService.findById(u.getId());
            s.setAttribute("user", updated != null ? updated : u);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IOException e) {
            return ResponseEntity.status(500).body(Map.of("error", "Failed to update password."));
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// ACTIVITY
// ═══════════════════════════════════════════════════════════════════
