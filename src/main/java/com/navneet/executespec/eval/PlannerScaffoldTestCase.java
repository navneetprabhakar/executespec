package com.navneet.executespec.eval;

import java.util.List;

/**
 * Extreme test scenarios for planner scaffold validation. Each scenario is designed to stress-test
 * the planner's project_setup sizing and ensure critical entry point files are never dropped.
 */
public record PlannerScaffoldTestCase(
    String id,
    String description,
    String userRequest,
    String expectedProjectType,
    List<ScaffoldCheck> checks) {

  /** A structural validation check on the generated plan. */
  public record ScaffoldCheck(
      String name, CheckType type, String value // task_id, complexity, file path substring, etc.
      ) {}

  public enum CheckType {
    /** Plan must contain a task with this task_id */
    TASK_EXISTS,
    /** Plan must NOT contain a task with this task_id */
    TASK_NOT_EXISTS,
    /** Task with given task_id must have this complexity */
    TASK_COMPLEXITY,
    /** Some task's owned_files must contain a file matching this substring */
    FILE_IN_PLAN,
    /** project_type field must equal this value */
    PROJECT_TYPE,
    /** Two tasks must exist: backend_setup + frontend_setup (FULL_STACK split) */
    FULL_STACK_SPLIT,
    /** No single task should own more than N files (value = max count as string) */
    MAX_FILES_PER_TASK
  }

  // ── Factory methods for extreme scenarios ─────────────────────────────────

  public static List<PlannerScaffoldTestCase> all() {
    return List.of(
        fullStackSpringBootReact(),
        fullStackDjangoVue(),
        springBootHeavy(),
        djangoPostgres(),
        nodeFrontendTsTailwind(),
        fullStackExpressReact());
  }

  /**
   * Scenario 1: FULL_STACK Java Spring Boot + React + TypeScript + Tailwind. This is the EXACT
   * scenario that failed in production (run 4468d6e2). The planner was putting 8+ files in a single
   * project_setup at LOW complexity.
   */
  static PlannerScaffoldTestCase fullStackSpringBootReact() {
    return new PlannerScaffoldTestCase(
        "full_stack_spring_react",
        "FULL_STACK: Spring Boot + React + TypeScript + Tailwind (the production failure scenario)",
        """
        Build a movie ticket booking web application.
        Backend: Java Spring Boot with Spring Data JPA, H2 database.
        - Movie entity with title, genre, duration, rating, poster URL, showtime
        - Booking entity with user name, email, movie, seats, showtime, status
        - REST API: CRUD for movies, create/cancel bookings, seat availability check
        - Global error handler with @RestControllerAdvice

        Frontend: React with TypeScript, Tailwind CSS, Vite.
        - Movie listing page with genre filter and search
        - Movie detail page with showtime selection
        - Booking form with seat picker (grid layout)
        - My Bookings page with cancel functionality
        - Responsive design, dark theme
        """,
        "FULL_STACK",
        List.of(
            new ScaffoldCheck("project_type", CheckType.PROJECT_TYPE, "FULL_STACK"),
            new ScaffoldCheck("full_stack_split", CheckType.FULL_STACK_SPLIT, null),
            new ScaffoldCheck(
                "no_single_project_setup", CheckType.TASK_NOT_EXISTS, "project_setup"),
            new ScaffoldCheck("backend_setup_exists", CheckType.TASK_EXISTS, "backend_setup"),
            new ScaffoldCheck("frontend_setup_exists", CheckType.TASK_EXISTS, "frontend_setup"),
            new ScaffoldCheck(
                "application_class_in_plan", CheckType.FILE_IN_PLAN, "Application.java"),
            new ScaffoldCheck("pom_in_plan", CheckType.FILE_IN_PLAN, "pom.xml"),
            new ScaffoldCheck("max_files_per_task", CheckType.MAX_FILES_PER_TASK, "6")));
  }

  /**
   * Scenario 2: FULL_STACK Python Django + Vue + Tailwind. Django has 4-5 scaffold files
   * (manage.py, settings.py, urls.py, wsgi.py, requirements.txt). Combined with Vue frontend
   * config, a single task would have 8+ files.
   */
  static PlannerScaffoldTestCase fullStackDjangoVue() {
    return new PlannerScaffoldTestCase(
        "full_stack_django_vue",
        "FULL_STACK: Django + Vue + Tailwind (Django scaffold is 5 files alone)",
        """
        Build an employee directory application.
        Backend: Python Django with Django REST Framework, PostgreSQL.
        - Employee model: name, email, department, title, hire_date, photo_url, phone
        - Department model: name, description, manager (FK to Employee)
        - REST API: list/search employees, filter by department, CRUD for admin
        - Token-based authentication

        Frontend: Vue 3 with Composition API, Tailwind CSS, Vite.
        - Employee directory grid with search and department filter
        - Employee profile card with detail modal
        - Department org chart view
        - Admin panel for adding/editing employees
        - Responsive, modern design
        """,
        "FULL_STACK",
        List.of(
            new ScaffoldCheck("project_type", CheckType.PROJECT_TYPE, "FULL_STACK"),
            new ScaffoldCheck("full_stack_split", CheckType.FULL_STACK_SPLIT, null),
            new ScaffoldCheck("manage_py_in_plan", CheckType.FILE_IN_PLAN, "manage.py"),
            new ScaffoldCheck("settings_in_plan", CheckType.FILE_IN_PLAN, "settings.py"),
            new ScaffoldCheck("max_files_per_task", CheckType.MAX_FILES_PER_TASK, "6")));
  }

  /**
   * Scenario 3: SPRING_BOOT standalone with heavy dependencies. Spring Boot scaffold needs pom.xml
   * + Application.java + application.yml + CorsConfig. Must be MEDIUM complexity, not LOW.
   */
  static PlannerScaffoldTestCase springBootHeavy() {
    return new PlannerScaffoldTestCase(
        "spring_boot_heavy",
        "SPRING_BOOT: Heavy Spring Boot API with many dependencies (tests MEDIUM complexity)",
        """
        Build a restaurant order management REST API with Spring Boot.
        - Spring Data JPA with H2 database
        - Entities: Restaurant, Menu, MenuItem, Order, OrderItem, Customer
        - Full CRUD REST APIs for all entities
        - Order placement with validation (menu item availability, restaurant hours)
        - Order status tracking (PLACED, PREPARING, READY, DELIVERED, CANCELLED)
        - Customer order history with pagination
        - @RestControllerAdvice for centralized error handling
        - Lombok for all entities
        - Spring Validation for request DTOs
        """,
        "SPRING_BOOT",
        List.of(
            new ScaffoldCheck("project_type", CheckType.PROJECT_TYPE, "SPRING_BOOT"),
            new ScaffoldCheck("application_class", CheckType.FILE_IN_PLAN, "Application.java"),
            new ScaffoldCheck("pom_xml", CheckType.FILE_IN_PLAN, "pom.xml"),
            new ScaffoldCheck("application_yml", CheckType.FILE_IN_PLAN, "application.yml"),
            new ScaffoldCheck("max_files_per_task", CheckType.MAX_FILES_PER_TASK, "6")));
  }

  /**
   * Scenario 4: PYTHON Django standalone with PostgreSQL. Django scaffold: manage.py + settings.py
   * + urls.py + wsgi.py + requirements.txt = 5 files. Must be MEDIUM complexity for Django.
   */
  static PlannerScaffoldTestCase djangoPostgres() {
    return new PlannerScaffoldTestCase(
        "django_postgres",
        "PYTHON: Django with PostgreSQL (tests MEDIUM escalation for Django scaffold)",
        """
Build a blog platform API with Django and Django REST Framework.
- PostgreSQL database
- Models: User (extend AbstractUser), Post, Comment, Category, Tag
- Post has title, slug, content, author, categories, tags, published_at, status (DRAFT/PUBLISHED)
- Comment has author, post, content, parent (nested comments)
- REST API: list posts with pagination, filter by category/tag, full CRUD
- Token authentication with registration and login
- Automatic slug generation from title
- Admin panel customization
""",
        "PYTHON",
        List.of(
            new ScaffoldCheck("project_type", CheckType.PROJECT_TYPE, "PYTHON"),
            new ScaffoldCheck("manage_py", CheckType.FILE_IN_PLAN, "manage.py"),
            new ScaffoldCheck("settings_py", CheckType.FILE_IN_PLAN, "settings.py"),
            new ScaffoldCheck("requirements", CheckType.FILE_IN_PLAN, "requirements"),
            new ScaffoldCheck("max_files_per_task", CheckType.MAX_FILES_PER_TASK, "6")));
  }

  /**
   * Scenario 5: NODE_FRONTEND with TypeScript + Tailwind + Vite. Tests the MEDIUM escalation rule
   * for 4+ config files.
   */
  static PlannerScaffoldTestCase nodeFrontendTsTailwind() {
    return new PlannerScaffoldTestCase(
        "node_frontend_ts_tailwind",
        "NODE_FRONTEND: React + TypeScript + Tailwind + Vite (tests MEDIUM escalation)",
        """
        Build a personal finance dashboard as a single-page React application.
        Use TypeScript, Tailwind CSS, Vite, and Recharts for charts.
        - Dashboard page: income vs expenses bar chart, category pie chart, net savings line chart
        - Transaction list with add/edit/delete, sortable columns
        - Category management (add, edit, delete categories with color coding)
        - Budget tracker with progress bars per category
        - Date range filter (this month, last month, custom range)
        - Dark mode toggle
        - Local storage for data persistence
        - Responsive design for mobile and desktop
        """,
        "NODE_FRONTEND",
        List.of(
            new ScaffoldCheck("project_type", CheckType.PROJECT_TYPE, "NODE_FRONTEND"),
            new ScaffoldCheck("package_json", CheckType.FILE_IN_PLAN, "package.json"),
            new ScaffoldCheck("vite_config", CheckType.FILE_IN_PLAN, "vite.config"),
            new ScaffoldCheck("tailwind_config", CheckType.FILE_IN_PLAN, "tailwind.config"),
            new ScaffoldCheck("max_files_per_task", CheckType.MAX_FILES_PER_TASK, "6")));
  }

  /**
   * Scenario 6: FULL_STACK Express + React (Node.js full-stack). Tests the two-task split for
   * non-Java full-stack projects.
   */
  static PlannerScaffoldTestCase fullStackExpressReact() {
    return new PlannerScaffoldTestCase(
        "full_stack_express_react",
        "FULL_STACK: Express + React + Tailwind (Node.js full-stack split test)",
        """
        Build a real-time task management application (like a simplified Trello).
        Backend: Node.js with Express, in-memory data store.
        - Board, Column, and Card entities
        - REST API: CRUD for boards, columns, cards
        - Card drag-and-drop reordering (position tracking)
        - WebSocket support for real-time updates across tabs
        - CORS middleware, JSON error handler, 404 handler

        Frontend: React with TypeScript, Tailwind CSS, Vite.
        - Board view with draggable columns and cards (react-beautiful-dnd)
        - Card detail modal with title, description, labels, due date
        - Board sidebar with list of boards
        - Create/edit board dialog
        - Real-time sync indicator
        - Responsive design
        """,
        "FULL_STACK",
        List.of(
            new ScaffoldCheck("project_type", CheckType.PROJECT_TYPE, "FULL_STACK"),
            new ScaffoldCheck("full_stack_split", CheckType.FULL_STACK_SPLIT, null),
            new ScaffoldCheck("package_json", CheckType.FILE_IN_PLAN, "package.json"),
            new ScaffoldCheck("max_files_per_task", CheckType.MAX_FILES_PER_TASK, "6")));
  }
}
