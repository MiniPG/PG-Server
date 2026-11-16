# MiniPG · PG-Server

가맹점의 결제 생성·승인·취소를 받아 카드사에 전달하는 결제대행(PG) 서버입니다. 결제 만료, 정산, 카드사 기록과 맞추는 대사를 포함합니다.

돈이 틀어지는 상황을 직접 재현하고, **문제 → 가설 → 확인 → 해결** 순서로 고친 과정을 [Wiki](https://github.com/MiniPG/PG-Server/wiki)에 기록했습니다.

![MiniPG 아키텍처](https://raw.githubusercontent.com/wiki/MiniPG/PG-Server/images/architecture-v2.png)

## 측정 기준
- **결제 1건** = 생성 → 승인 → 조회, 20%는 부분 취소 포함
- **SLO** = 서버 측정 p99, 생성·조회 100ms, 승인·취소 800ms
- **카드사**는 장애를 주입할 수 있게 직접 만든 Mock 서버([Card-Server](https://github.com/MiniPG/Card-Server))입니다. 카드사 동작에 기댄 보장은 이 Mock의 계약(같은 거래 번호는 한 번만 처리)을 전제로 합니다.

## 주요 문제 해결

### 01. 카드사가 응답하지 않은 결제를 돈 오차 없이 정리
결과 모르는 결제 1,000건 정리 10분 22초 → 15.1초, 중복 망취소 0, 금액 오차 0

- [문제해결-08-카드사-응답이-없으면-결제가-APPROVING에-갇힘](https://github.com/MiniPG/PG-Server/wiki/%EB%AC%B8%EC%A0%9C%ED%95%B4%EA%B2%B0-08-%EC%B9%B4%EB%93%9C%EC%82%AC-%EC%9D%91%EB%8B%B5%EC%9D%B4-%EC%97%86%EC%9C%BC%EB%A9%B4-%EA%B2%B0%EC%A0%9C%EA%B0%80-APPROVING%EC%97%90-%EA%B0%87%ED%9E%98)
- [카드사-문제해결-03-응답-없는-승인을-조회로-확정하면-늦게-도착한-승인이-처리됨](https://github.com/MiniPG/PG-Server/wiki/%EC%B9%B4%EB%93%9C%EC%82%AC-%EB%AC%B8%EC%A0%9C%ED%95%B4%EA%B2%B0-03-%EC%9D%91%EB%8B%B5-%EC%97%86%EB%8A%94-%EC%8A%B9%EC%9D%B8%EC%9D%84-%EC%A1%B0%ED%9A%8C%EB%A1%9C-%ED%99%95%EC%A0%95%ED%95%98%EB%A9%B4-%EB%8A%A6%EA%B2%8C-%EB%8F%84%EC%B0%A9%ED%95%9C-%EC%8A%B9%EC%9D%B8%EC%9D%B4-%EC%B2%98%EB%A6%AC%EB%90%A8)
- [문제해결-10-실시간-대사가-결과-모르는-승인-1,000건을-10분-넘게-붙잡음](https://github.com/MiniPG/PG-Server/wiki/%EB%AC%B8%EC%A0%9C%ED%95%B4%EA%B2%B0-10-%EC%8B%A4%EC%8B%9C%EA%B0%84-%EB%8C%80%EC%82%AC%EA%B0%80-%EA%B2%B0%EA%B3%BC-%EB%AA%A8%EB%A5%B4%EB%8A%94-%EC%8A%B9%EC%9D%B8-1%2C000%EA%B1%B4%EC%9D%84-10%EB%B6%84-%EB%84%98%EA%B2%8C-%EB%B6%99%EC%9E%A1%EC%9D%8C)
- [문제해결-11-PG를-2대로-늘리자-실시간-대사가-같은-거래를-두-번-망취소함](https://github.com/MiniPG/PG-Server/wiki/%EB%AC%B8%EC%A0%9C%ED%95%B4%EA%B2%B0-11-PG%EB%A5%BC-2%EB%8C%80%EB%A1%9C-%EB%8A%98%EB%A6%AC%EC%9E%90-%EC%8B%A4%EC%8B%9C%EA%B0%84-%EB%8C%80%EC%82%AC%EA%B0%80-%EA%B0%99%EC%9D%80-%EA%B1%B0%EB%9E%98%EB%A5%BC-%EB%91%90-%EB%B2%88-%EB%A7%9D%EC%B7%A8%EC%86%8C%ED%95%A8)

### 02. 같은 요청이 두 번 와도 돈이 맞게
동시 승인 5번 → 1번, 동시 취소 환불 15,000원 → 9,000원, 재요청 중복 환불 0

- [문제해결-05-동시-승인으로-카드사-승인이-여러-번-일어남](https://github.com/MiniPG/PG-Server/wiki/%EB%AC%B8%EC%A0%9C%ED%95%B4%EA%B2%B0-05-%EB%8F%99%EC%8B%9C-%EC%8A%B9%EC%9D%B8%EC%9C%BC%EB%A1%9C-%EC%B9%B4%EB%93%9C%EC%82%AC-%EC%8A%B9%EC%9D%B8%EC%9D%B4-%EC%97%AC%EB%9F%AC-%EB%B2%88-%EC%9D%BC%EC%96%B4%EB%82%A8)
- [문제해결-06-동시-부분-취소로-결제-금액보다-많이-환불됨](https://github.com/MiniPG/PG-Server/wiki/%EB%AC%B8%EC%A0%9C%ED%95%B4%EA%B2%B0-06-%EB%8F%99%EC%8B%9C-%EB%B6%80%EB%B6%84-%EC%B7%A8%EC%86%8C%EB%A1%9C-%EA%B2%B0%EC%A0%9C-%EA%B8%88%EC%95%A1%EB%B3%B4%EB%8B%A4-%EB%A7%8E%EC%9D%B4-%ED%99%98%EB%B6%88%EB%90%A8)
- [문제해결-09-테스트-DB를-MySQL로-바꾸자-동시-요청의-거절-응답이-틀림](https://github.com/MiniPG/PG-Server/wiki/%EB%AC%B8%EC%A0%9C%ED%95%B4%EA%B2%B0-09-%ED%85%8C%EC%8A%A4%ED%8A%B8-DB%EB%A5%BC-MySQL%EB%A1%9C-%EB%B0%94%EA%BE%B8%EC%9E%90-%EB%8F%99%EC%8B%9C-%EC%9A%94%EC%B2%AD%EC%9D%98-%EA%B1%B0%EC%A0%88-%EC%9D%91%EB%8B%B5%EC%9D%B4-%ED%8B%80%EB%A6%BC)
- [문제해결-13-응답을-못-받은-가맹점이-부분-취소를-다시-보내면-한-번-더-환불됨](https://github.com/MiniPG/PG-Server/wiki/%EB%AC%B8%EC%A0%9C%ED%95%B4%EA%B2%B0-13-%EC%9D%91%EB%8B%B5%EC%9D%84-%EB%AA%BB-%EB%B0%9B%EC%9D%80-%EA%B0%80%EB%A7%B9%EC%A0%90%EC%9D%B4-%EB%B6%80%EB%B6%84-%EC%B7%A8%EC%86%8C%EB%A5%BC-%EB%8B%A4%EC%8B%9C-%EB%B3%B4%EB%82%B4%EB%A9%B4-%ED%95%9C-%EB%B2%88-%EB%8D%94-%ED%99%98%EB%B6%88%EB%90%A8)

### 03. 돈의 기록이 어긋나지 않게 남기고, 매일 맞춰 보기
요청 단위 원장과 상태 전이 표, 시각 6자리 통일, 거래 번호 기준 D+1 대사

- [설계-결제-상태와-거래-원장](https://github.com/MiniPG/PG-Server/wiki/%EC%84%A4%EA%B3%84-%EA%B2%B0%EC%A0%9C-%EC%83%81%ED%83%9C%EC%99%80-%EA%B1%B0%EB%9E%98-%EC%9B%90%EC%9E%A5)
- [문제해결-07-만료-스케줄러가-승인-처리-중인-결제를-EXPIRED로-덮어씀](https://github.com/MiniPG/PG-Server/wiki/%EB%AC%B8%EC%A0%9C%ED%95%B4%EA%B2%B0-07-%EB%A7%8C%EB%A3%8C-%EC%8A%A4%EC%BC%80%EC%A4%84%EB%9F%AC%EA%B0%80-%EC%8A%B9%EC%9D%B8-%EC%B2%98%EB%A6%AC-%EC%A4%91%EC%9D%B8-%EA%B2%B0%EC%A0%9C%EB%A5%BC-EXPIRED%EB%A1%9C-%EB%8D%AE%EC%96%B4%EC%94%80)
- [문제해결-04-자정-직전-거래가-다음-날-정산에-들어감](https://github.com/MiniPG/PG-Server/wiki/%EB%AC%B8%EC%A0%9C%ED%95%B4%EA%B2%B0-04-%EC%9E%90%EC%A0%95-%EC%A7%81%EC%A0%84-%EA%B1%B0%EB%9E%98%EA%B0%80-%EB%8B%A4%EC%9D%8C-%EB%82%A0-%EC%A0%95%EC%82%B0%EC%97%90-%EB%93%A4%EC%96%B4%EA%B0%90)
- [배치-D+1-거래-대사](https://github.com/MiniPG/PG-Server/wiki/%EB%B0%B0%EC%B9%98-D%2B1-%EA%B1%B0%EB%9E%98-%EB%8C%80%EC%82%AC)

### 04. 카드사 장애에서 무너지지 않게
카드사 무응답 중 결제 생성 p99 26.75초 → 0.14초

- [문제해결-12-카드사가-느려지면-카드사와-무관한-결제-생성과-조회까지-멈춤](https://github.com/MiniPG/PG-Server/wiki/%EB%AC%B8%EC%A0%9C%ED%95%B4%EA%B2%B0-12-%EC%B9%B4%EB%93%9C%EC%82%AC%EA%B0%80-%EB%8A%90%EB%A0%A4%EC%A7%80%EB%A9%B4-%EC%B9%B4%EB%93%9C%EC%82%AC%EC%99%80-%EB%AC%B4%EA%B4%80%ED%95%9C-%EA%B2%B0%EC%A0%9C-%EC%83%9D%EC%84%B1%EA%B3%BC-%EC%A1%B0%ED%9A%8C%EA%B9%8C%EC%A7%80-%EB%A9%88%EC%B6%A4)
- [부하테스트-13-선착순-스파이크-평소의-10배가-1초-만에-몰리면-모든-API가-같은-줄에-섬](https://github.com/MiniPG/PG-Server/wiki/%EB%B6%80%ED%95%98%ED%85%8C%EC%8A%A4%ED%8A%B8-13-%EC%84%A0%EC%B0%A9%EC%88%9C-%EC%8A%A4%ED%8C%8C%EC%9D%B4%ED%81%AC-%ED%8F%89%EC%86%8C%EC%9D%98-10%EB%B0%B0%EA%B0%80-1%EC%B4%88-%EB%A7%8C%EC%97%90-%EB%AA%B0%EB%A6%AC%EB%A9%B4-%EB%AA%A8%EB%93%A0-API%EA%B0%80-%EA%B0%99%EC%9D%80-%EC%A4%84%EC%97%90-%EC%84%AC)

### 05. 서버 1대·DB 1대로 최대한 버티기
제어 쿼리 제거(DB 쿼리 −31%), HTTP 클라이언트 교체(서버 CPU −17%), 효과가 측정된 변경만 남김

- [부하테스트-문제해결-01-OSIV가-카드사-대기-중-커넥션을-쥐어-초당-14회에서-무너짐](https://github.com/MiniPG/PG-Server/wiki/%EB%B6%80%ED%95%98%ED%85%8C%EC%8A%A4%ED%8A%B8-%EB%AC%B8%EC%A0%9C%ED%95%B4%EA%B2%B0-01-OSIV%EA%B0%80-%EC%B9%B4%EB%93%9C%EC%82%AC-%EB%8C%80%EA%B8%B0-%EC%A4%91-%EC%BB%A4%EB%84%A5%EC%85%98%EC%9D%84-%EC%A5%90%EC%96%B4-%EC%B4%88%EB%8B%B9-14%ED%9A%8C%EC%97%90%EC%84%9C-%EB%AC%B4%EB%84%88%EC%A7%90)
- [부하테스트-문제해결-04-쿼리-하나짜리-조회가-제어-쿼리를-10개-보냄](https://github.com/MiniPG/PG-Server/wiki/%EB%B6%80%ED%95%98%ED%85%8C%EC%8A%A4%ED%8A%B8-%EB%AC%B8%EC%A0%9C%ED%95%B4%EA%B2%B0-04-%EC%BF%BC%EB%A6%AC-%ED%95%98%EB%82%98%EC%A7%9C%EB%A6%AC-%EC%A1%B0%ED%9A%8C%EA%B0%80-%EC%A0%9C%EC%96%B4-%EC%BF%BC%EB%A6%AC%EB%A5%BC-10%EA%B0%9C-%EB%B3%B4%EB%83%84)
- [부하테스트-문제해결-05-구조화-로그가-글자마다-UTF-8-인코더를-부름](https://github.com/MiniPG/PG-Server/wiki/%EB%B6%80%ED%95%98%ED%85%8C%EC%8A%A4%ED%8A%B8-%EB%AC%B8%EC%A0%9C%ED%95%B4%EA%B2%B0-05-%EA%B5%AC%EC%A1%B0%ED%99%94-%EB%A1%9C%EA%B7%B8%EA%B0%80-%EA%B8%80%EC%9E%90%EB%A7%88%EB%8B%A4-UTF-8-%EC%9D%B8%EC%BD%94%EB%8D%94%EB%A5%BC-%EB%B6%80%EB%A6%84)
- [부하테스트-문제해결-06-카드사-호출마다-스레드를-2개씩-새로-만듦](https://github.com/MiniPG/PG-Server/wiki/%EB%B6%80%ED%95%98%ED%85%8C%EC%8A%A4%ED%8A%B8-%EB%AC%B8%EC%A0%9C%ED%95%B4%EA%B2%B0-06-%EC%B9%B4%EB%93%9C%EC%82%AC-%ED%98%B8%EC%B6%9C%EB%A7%88%EB%8B%A4-%EC%8A%A4%EB%A0%88%EB%93%9C%EB%A5%BC-2%EA%B0%9C%EC%94%A9-%EC%83%88%EB%A1%9C-%EB%A7%8C%EB%93%A6)
- [부하테스트-문제해결-09-모든-요청의-가맹점-인증이-PG-CPU의-13퍼센트를-씀](https://github.com/MiniPG/PG-Server/wiki/%EB%B6%80%ED%95%98%ED%85%8C%EC%8A%A4%ED%8A%B8-%EB%AC%B8%EC%A0%9C%ED%95%B4%EA%B2%B0-09-%EB%AA%A8%EB%93%A0-%EC%9A%94%EC%B2%AD%EC%9D%98-%EA%B0%80%EB%A7%B9%EC%A0%90-%EC%9D%B8%EC%A6%9D%EC%9D%B4-PG-CPU%EC%9D%98-13%ED%8D%BC%EC%84%BC%ED%8A%B8%EB%A5%BC-%EC%94%80)

### 06. 트래픽에 맞춰 인프라 구조 바꾸기
SLO 안 처리량 초당 결제 10 → 200(1대) → 320건(2대), 스파이크 300 → 376건/초

- [부하테스트-10-한-대의-한계와-먼저-막힌-자원](https://github.com/MiniPG/PG-Server/wiki/%EB%B6%80%ED%95%98%ED%85%8C%EC%8A%A4%ED%8A%B8-10-%ED%95%9C-%EB%8C%80%EC%9D%98-%ED%95%9C%EA%B3%84%EC%99%80-%EB%A8%BC%EC%A0%80-%EB%A7%89%ED%9E%8C-%EC%9E%90%EC%9B%90)
- [부하테스트-12-PG-2대로-늘린-뒤-한계와-먼저-막힌-자원](https://github.com/MiniPG/PG-Server/wiki/%EB%B6%80%ED%95%98%ED%85%8C%EC%8A%A4%ED%8A%B8-12-PG-2%EB%8C%80%EB%A1%9C-%EB%8A%98%EB%A6%B0-%EB%92%A4-%ED%95%9C%EA%B3%84%EC%99%80-%EB%A8%BC%EC%A0%80-%EB%A7%89%ED%9E%8C-%EC%9E%90%EC%9B%90)
- [부하테스트-실험-03-멱등-키-비용을-같은-인스턴스에서-재고-COMMIT-외-문장을-셈](https://github.com/MiniPG/PG-Server/wiki/%EB%B6%80%ED%95%98%ED%85%8C%EC%8A%A4%ED%8A%B8-%EC%8B%A4%ED%97%98-03-%EB%A9%B1%EB%93%B1-%ED%82%A4-%EB%B9%84%EC%9A%A9%EC%9D%84-%EA%B0%99%EC%9D%80-%EC%9D%B8%EC%8A%A4%ED%84%B4%EC%8A%A4%EC%97%90%EC%84%9C-%EC%9E%AC%EA%B3%A0-COMMIT-%EC%99%B8-%EB%AC%B8%EC%9E%A5%EC%9D%84-%EC%85%88)
- [부하테스트-실험-04-DB만-2배로-키우자-스파이크-p99가-6분의-1이-되고-벽이-PG-CPU로-옮겨감](https://github.com/MiniPG/PG-Server/wiki/%EB%B6%80%ED%95%98%ED%85%8C%EC%8A%A4%ED%8A%B8-%EC%8B%A4%ED%97%98-04-DB%EB%A7%8C-2%EB%B0%B0%EB%A1%9C-%ED%82%A4%EC%9A%B0%EC%9E%90-%EC%8A%A4%ED%8C%8C%EC%9D%B4%ED%81%AC-p99%EA%B0%80-6%EB%B6%84%EC%9D%98-1%EC%9D%B4-%EB%90%98%EA%B3%A0-%EB%B2%BD%EC%9D%B4-PG-CPU%EB%A1%9C-%EC%98%AE%EA%B2%A8%EA%B0%90)

## Wiki 둘러보기
| 분류 | 내용 | 시작 페이지 |
|---|---|---|
| API · 배치 | 결제 생성·승인·조회·취소, 정산, 만료, 실시간·D+1 대사 | [API-02-결제-승인](https://github.com/MiniPG/PG-Server/wiki/API-02-%EA%B2%B0%EC%A0%9C-%EC%8A%B9%EC%9D%B8) |
| 설계 | 결제 상태와 거래 원장, 요청과 돈의 흐름, 인프라 | [설계-요청과-돈의-흐름](https://github.com/MiniPG/PG-Server/wiki/%EC%84%A4%EA%B3%84-%EC%9A%94%EC%B2%AD%EA%B3%BC-%EB%8F%88%EC%9D%98-%ED%9D%90%EB%A6%84) |
| 문제 해결 | 기능을 만들며 재현하고 고친 문제 13편 | [문제해결-05](https://github.com/MiniPG/PG-Server/wiki/%EB%AC%B8%EC%A0%9C%ED%95%B4%EA%B2%B0-05-%EB%8F%99%EC%8B%9C-%EC%8A%B9%EC%9D%B8%EC%9C%BC%EB%A1%9C-%EC%B9%B4%EB%93%9C%EC%82%AC-%EC%8A%B9%EC%9D%B8%EC%9D%B4-%EC%97%AC%EB%9F%AC-%EB%B2%88-%EC%9D%BC%EC%96%B4%EB%82%A8) |
| 부하 테스트 | SLO, 용량 측정 13편, 병목 해결 10편, 실험 4편 | [설계-SLO](https://github.com/MiniPG/PG-Server/wiki/%EC%84%A4%EA%B3%84-SLO) |
| 개념 정리 | InnoDB 락, MVCC, OSIV, 리틀의 법칙 등 | [개념-InnoDB-락-종류](https://github.com/MiniPG/PG-Server/wiki/%EA%B0%9C%EB%85%90-InnoDB-%EB%9D%BD-%EC%A2%85%EB%A5%98) |

## Tech Stack
- **Backend** Java 21, Spring Boot 4, JPA, MySQL 8, Testcontainers
- **Infra · 모니터링** AWS(EC2, ALB, ECR), Terraform, GitHub Actions, k6, Prometheus, Grafana, Loki, JFR

## 관련 저장소
- [Card-Server](https://github.com/MiniPG/Card-Server) · 장애 주입이 가능한 Mock 카드사
- [Infra](https://github.com/MiniPG/Infra) · Terraform
- [LoadTest](https://github.com/MiniPG/LoadTest) · k6 시나리오
- [Monitoring](https://github.com/MiniPG/Monitoring) · Prometheus·Grafana 설정
