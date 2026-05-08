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

class ExtraController {
    private final FoodItemService foodService;
    private final FileStorageUtil fsu;

    @Autowired ExtraController(FoodItemService fs, FileStorageUtil fsu) {
        this.foodService = fs; this.fsu = fsu;
    }


    @GetMapping("/about")   public String about(HttpSession s, Model m)  { m.addAttribute("user", s.getAttribute("user")); return "about/index"; }
    @GetMapping("/reviews") public String reviews(HttpSession s, Model m) {
        List<Feedback> reviews = fsu.readAll(fsu.getFeedbackFile()).stream()
            .map(Feedback::fromFileLine).filter(f -> f != null && f.isPublicFeedback())
            .sorted(Comparator.comparing((Feedback f) -> f.getCreatedAt() != null ? f.getCreatedAt() : "", Comparator.reverseOrder()))
            .collect(Collectors.toList());
        m.addAttribute("reviews", reviews); m.addAttribute("user", s.getAttribute("user"));
        return "reviews/index";
    }

    @GetMapping("/contact")  public String contact(HttpSession s, Model m)  { m.addAttribute("user", s.getAttribute("user")); return "contact/index"; }
    @PostMapping("/contact")
    public String submitContact(@RequestParam String contactName,
                                @RequestParam String contactEmail,
                                @RequestParam String message, Model m) throws IOException {
        String id = "CON" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        fsu.appendLine(fsu.getContactsFile(),
            id + "|" + contactName + "|" + contactEmail + "|" +
            message.replace("|", "") + "|" +
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        m.addAttribute("success", "Thank you! We'll get back to you soon.");
        return "contact/index";
    }
}
