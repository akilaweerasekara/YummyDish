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

@Controller
@RequestMapping("/admin")
class AdminAuthController {
    private final UserService userService;
    private final FoodItemService foodService;
    private final FileStorageUtil fsu;
    private final OfferService offerService;
    private final com.yummydish.util.OrderQueue orderQueue;

    @Autowired
    AdminAuthController(UserService us, FoodItemService fs,
                        FileStorageUtil fsu, OfferService os,
                        com.yummydish.util.OrderQueue oq) {
        this.userService = us; this.foodService = fs;
        this.fsu = fsu;       this.offerService = os;
        this.orderQueue = oq;
    }

    private boolean isAdmin(HttpSession s) {
        Object o = s.getAttribute("admin");
        return o instanceof User u && "ADMIN".equals(u.getRole());
    }

    @GetMapping("/login")
    public String adminLoginPage(HttpSession s) {
        return isAdmin(s) ? "redirect:/admin/dashboard" : "admin/login";
    }

    @PostMapping("/login")
    public String doAdminLogin(@RequestParam String email,
                               @RequestParam String password,
                               HttpSession s, Model m) {
        User u = userService.authenticate(email, password);
        if (u == null || !"ADMIN".equals(u.getRole())) {
            m.addAttribute("error", "Invalid admin credentials.");
            return "admin/login";
        }
        s.setAttribute("admin", u);
        return "redirect:/admin/dashboard";
    }

    @GetMapping("/logout")
    public String adminLogout(HttpSession s) {
        s.removeAttribute("admin");
        return "redirect:/admin/login";
    }

    @GetMapping("/dashboard")
    public String dashboard(HttpSession s, Model m) {
        if (!isAdmin(s)) return "redirect:/admin/login";

        List<Order> orders = fsu.readAll(fsu.getOrdersFile()).stream()
            .map(Order::fromLine).filter(Objects::nonNull)
            .sorted(Comparator.comparing(
                (Order o) -> o.getCreatedAt() != null ? o.getCreatedAt() : "",
                Comparator.reverseOrder()))
            .collect(Collectors.toList());

        long revenue = orders.stream().mapToLong(o -> (long) o.getTotalAmount()).sum();

        List<Feedback> feedbacks = fsu.readAll(fsu.getFeedbackFile()).stream()
            .map(Feedback::fromFileLine).filter(Objects::nonNull).collect(Collectors.toList());

        String today = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        m.addAttribute("admin",          s.getAttribute("admin"));
        m.addAttribute("user",           s.getAttribute("admin"));
        m.addAttribute("foods",          foodService.getAll());
        m.addAttribute("customers",      userService.getAllCustomers());
        m.addAttribute("drivers",        userService.getAllDrivers());
        m.addAttribute("orders",         orders);
        m.addAttribute("feedback",       feedbacks);
        m.addAttribute("offers",         offerService.getAll());
        m.addAttribute("revenue",        revenue);
        m.addAttribute("contacts",       fsu.readAll(fsu.getContactsFile()));
        m.addAttribute("cookingOrders",  orders.stream().filter(o -> Order.COOKING.equals(o.getStatus())).collect(Collectors.toList()));
        m.addAttribute("readyOrders",    orders.stream().filter(o -> Order.READY.equals(o.getStatus())).collect(Collectors.toList()));
        m.addAttribute("onwayOrders",    orders.stream().filter(o -> Order.ONWAY.equals(o.getStatus()) || Order.HANDOVER.equals(o.getStatus())).collect(Collectors.toList()));
        m.addAttribute("deliveredToday", orders.stream().filter(o -> Order.DELIVERED.equals(o.getStatus()) && o.getCreatedAt() != null && o.getCreatedAt().startsWith(today)).collect(Collectors.toList()));
        return "admin/dashboard";
    }

    // ── Food management ───────────────────────────────────────────
    @PostMapping("/food/add")
    public String addFood(@RequestParam String name, @RequestParam String description,
                          @RequestParam double price, @RequestParam String category,
                          @RequestParam(defaultValue = "") String ingredients,
                          @RequestParam(defaultValue = "—") String portionSize,
                          @RequestParam(defaultValue = "0") int calories,
                          @RequestParam(required = false, defaultValue = "") String imageUrl,
                          @RequestParam(defaultValue = "MainCourse") String foodType,
                          HttpSession s) throws IOException {
        if (!isAdmin(s)) return "redirect:/admin/login";
        foodService.add(name, description, price, category, ingredients,
                        portionSize, calories, imageUrl, foodType);
        return "redirect:/admin/dashboard?tab=food";
    }

    @PostMapping("/food/delete")
    public String deleteFood(@RequestParam String id, HttpSession s) throws IOException {
        if (!isAdmin(s)) return "redirect:/admin/login";
        foodService.delete(id);
        return "redirect:/admin/dashboard?tab=food";
    }

    @PostMapping("/food/toggle")
    public String toggleFood(@RequestParam String id, HttpSession s) throws IOException {
        if (!isAdmin(s)) return "redirect:/admin/login";
        foodService.toggleAvailability(id);
        return "redirect:/admin/dashboard?tab=food";
    }

    @GetMapping("/food/get/{id}")
    public ResponseEntity<?> getFoodJson(@PathVariable String id, HttpSession s) {
        if (!isAdmin(s)) return ResponseEntity.status(401).build();
        var f = foodService.getById(id);
        if (f == null) return ResponseEntity.notFound().build();
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("id", f.getId()); m.put("name", f.getName()); m.put("description", f.getDescription());
        m.put("price", f.getPrice()); m.put("category", f.getCategory());
        m.put("ingredients", f.getIngredients() != null ? f.getIngredients() : "");
        m.put("portionSize", f.getPortionSize() != null ? f.getPortionSize() : "");
        m.put("calories", f.getCalories()); m.put("imageUrl", f.getImageUrl() != null ? f.getImageUrl() : "");
        m.put("available", f.isAvailable()); m.put("popular", f.isPopular());
        m.put("foodType", f.getFoodType());
        return ResponseEntity.ok(m);
    }

    @PostMapping("/food/update")
    public String updateFood(@RequestParam String id,
                             @RequestParam String name, @RequestParam String description,
                             @RequestParam double price, @RequestParam String category,
                             @RequestParam(defaultValue="") String ingredients,
                             @RequestParam(defaultValue="—") String portionSize,
                             @RequestParam(defaultValue="0") int calories,
                             @RequestParam(defaultValue="") String imageUrl,
                             @RequestParam(defaultValue="MainCourse") String foodType,
                             @RequestParam(defaultValue="true") boolean available,
                             HttpSession s) throws IOException {
        if (!isAdmin(s)) return "redirect:/admin/login";
        foodService.update(id, name, description, price, category, ingredients, portionSize, calories, available, imageUrl);
        return "redirect:/admin/dashboard?tab=food";
    }

    @PostMapping("/user/update")
    public String adminUpdateUser(@RequestParam String id,
                                  @RequestParam(defaultValue="") String name,
                                  @RequestParam(defaultValue="") String phone,
                                  @RequestParam(defaultValue="") String address,
                                  @RequestParam(defaultValue="") String profilePicUrl,
                                  HttpSession s) throws IOException {
        if (!isAdmin(s)) return "redirect:/admin/login";
        var u = userService.findById(id);
        if (u != null) {
            if (!name.isBlank())         u.setName(name);
            if (!phone.isBlank())        u.setPhone(phone);
            if (!address.isBlank())      u.setAddress(address);
            if (!profilePicUrl.isBlank()) u.setProfilePicUrl(profilePicUrl);
            fsu.update(fsu.getUsersFile(), id, u.toFileLine());
        }
        // Return to correct tab based on role
        var user = userService.findById(id);
        String tab = (user != null && "DRIVER".equals(user.getRole())) ? "drivers" : "users";
        return "redirect:/admin/dashboard?tab=" + tab;
    }

    // ── Order status — MANUAL only, admin controls this ──────────
    @PostMapping("/order/status")
    public String updateOrderStatus(@RequestParam String orderId,
                                    @RequestParam String status,
                                    HttpSession s) throws IOException {
        if (!isAdmin(s)) return "redirect:/admin/login";
        String line = fsu.findById(fsu.getOrdersFile(), orderId);
        if (line != null) {
            Order o = Order.fromLine(line);
            o.setStatus(status);
            o.setUpdatedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));

            // ── OrderQueue: dequeue when kitchen starts cooking ────────────
            // When admin moves order to COOKING it leaves the waiting queue
            // and enters active kitchen processing — FIFO order preserved.
            if (Order.COOKING.equals(status)) {
                orderQueue.removeById(orderId);
                System.out.println("[OrderQueue] Dequeued " + orderId
                    + " → COOKING | Remaining in queue: " + orderQueue.size());
            }

            // Auto-assign least-loaded driver when moving to READY/HANDOVER
            if ((Order.READY.equals(status) || Order.HANDOVER.equals(status))
                    && (o.getDriverId() == null || o.getDriverId().isBlank())) {
                User assignedDriver = findLeastLoadedDriver();
                if (assignedDriver != null) {
                    o.setDriverId(assignedDriver.getId());
                    o.setDriverName(assignedDriver.getName());
                    o.setDriverContact(assignedDriver.getPhone() != null ? assignedDriver.getPhone() : "");
                }
            }
            fsu.update(fsu.getOrdersFile(), orderId, o.toFileLine());
        }
        return "redirect:/admin/dashboard?tab=orders";
    }

    /** Load-balance drivers: assign to the one with fewest active orders */
    private User findLeastLoadedDriver() {
        List<User> drivers = userService.getAllDrivers();
        if (drivers.isEmpty()) return null;
        Map<String, Long> load = fsu.readAll(fsu.getOrdersFile()).stream()
            .map(Order::fromLine).filter(Objects::nonNull)
            .filter(o -> !Order.DELIVERED.equals(o.getStatus()) && !Order.CANCELLED.equals(o.getStatus())
                      && o.getDriverId() != null && !o.getDriverId().isBlank())
            .collect(java.util.stream.Collectors.groupingBy(Order::getDriverId, java.util.stream.Collectors.counting()));
        return drivers.stream()
            .min(Comparator.comparingLong(d -> load.getOrDefault(d.getId(), 0L)))
            .orElse(null);
    }

    // ── User management ───────────────────────────────────────────
    @PostMapping("/user/delete")
    public String deleteUser(@RequestParam String id, HttpSession s) throws IOException {
        if (!isAdmin(s)) return "redirect:/admin/login";
        userService.delete(id);
        return "redirect:/admin/dashboard?tab=users";
    }

    // ── Driver management ─────────────────────────────────────────
    @PostMapping("/driver/add")
    public String addDriver(@RequestParam String name,
                            @RequestParam String email,
                            @RequestParam String password,
                            @RequestParam(defaultValue = "") String phone,
                            HttpSession s, Model m) throws IOException {
        if (!isAdmin(s)) return "redirect:/admin/login";
        try {
            userService.registerDriver(name, email, password, phone);
        } catch (IllegalArgumentException e) {
            // email already taken — just redirect back with tab
        }
        return "redirect:/admin/dashboard?tab=drivers";
    }

    // ── Offer management ──────────────────────────────────────────
    @PostMapping("/offer/add")
    public String addOffer(@RequestParam String code, @RequestParam String title,
                           @RequestParam String description,
                           @RequestParam double discount,
                           @RequestParam(defaultValue = "0") double minOrder,
                           HttpSession s) throws IOException {
        if (!isAdmin(s)) return "redirect:/admin/login";
        Offer o = new Offer();
        o.setCode(code.toUpperCase()); o.setTitle(title); o.setDescription(description);
        o.setDiscountPercent(discount); o.setMinOrderAmount(minOrder); o.setActive(true);
        o.setCreatedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        offerService.add(o);
        return "redirect:/admin/dashboard?tab=offers";
    }

    @PostMapping("/offer/delete")
    public String deleteOffer(@RequestParam String code, HttpSession s) throws IOException {
        if (!isAdmin(s)) return "redirect:/admin/login";
        offerService.delete(code);
        return "redirect:/admin/dashboard?tab=offers";
    }

    // ── Feedback reply ────────────────────────────────────────────
    @PostMapping("/feedback/reply")
    public String replyFeedback(@RequestParam String feedbackId,
                                @RequestParam String reply,
                                HttpSession s) throws IOException {
        if (!isAdmin(s)) return "redirect:/admin/login";
        String line = fsu.findById(fsu.getFeedbackFile(), feedbackId);
        if (line != null) {
            String[] parts = line.split("\\|", -1);
            if (parts.length >= 8) {
                parts[7] = reply.replace("|", "").replace("\n", " ");
                fsu.update(fsu.getFeedbackFile(), feedbackId, String.join("|", parts));
            }
        }
        return "redirect:/admin/dashboard?tab=feedback";
    }
}

// ═══════════════════════════════════════════════════════════════════
// DRIVER AUTH — /driver/login (completely separate from customer)
// ═══════════════════════════════════════════════════════════════════
