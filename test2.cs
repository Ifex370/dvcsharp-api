using AlternateChannels.API.Extensions;
using AlternateChannels.API.Filters;
using Application.Behaviours;
using Application.Features.Customer.Query;
using Application.Features.Customer.Validations.CustomerValidation;
using FluentValidation;
using MediatR;
using RepoDb;
using RepoDb.DbHelpers;
using RepoDb.DbSettings;
using RepoDb.StatementBuilders;
using Serilog;
using System.Reflection;
var builder = WebApplication.CreateBuilder(args);
//Serilog cofiguration
Log.Logger = new LoggerConfiguration()
    .CreateBootstrapLogger();
builder.Host.UseSerilog(((ctx, lc) => lc
.ReadFrom.Configuration(ctx.Configuration)));
// Add services to the container.
builder.Services.AddControllers();
// Learn more about configuring Swagger/OpenAPI at https://aka.ms/aspnetcore/swashbuckle
builder.Services.AddEndpointsApiExplorer();
var xmlFile = $"{Assembly.GetExecutingAssembly().GetName().Name}.xml";
var xmlPath = Path.Combine(AppContext.BaseDirectory, xmlFile);
builder.Services.AddSwaggerGen(c =>
{
    c.IncludeXmlComments(xmlPath);
    c.OperationFilter<AddRequiredHeaderParameter>();
});
builder.Services.AddMediatR(typeof(CustomerChannelsQueryValidator).Assembly);
builder.Services.AddMediatR(typeof(CustomerChannelsQuery).Assembly);
builder.Services.AddValidatorsFromAssembly(typeof(CustomerChannelsQueryValidator).Assembly);
builder.Services.AddTransient(typeof(IPipelineBehavior<,>), typeof(ValidationBehaviour<,>));
builder.Services.AddApplicationLayer(builder.Configuration);
var app = builder.Build();
// Configure the HTTP request pipeline.
if (app.Environment.IsDevelopment())
{
    app.UseSwagger();
    app.UseSwaggerUI();
}
app.UseSwagger();
app.UseSwaggerUI();
////<===== Configure RepoDb ===>
var dbSetting = new SqlServerDbSetting();
DbSettingMapper.Add<System.Data.SqlClient.SqlConnection>(dbSetting, true);
DbHelperMapper.Add<System.Data.SqlClient.SqlConnection>(new SqlServerDbHelper(), true);
StatementBuilderMapper.Add<System.Data.SqlClient.SqlConnection>(new SqlServerStatementBuilder(dbSetting), true);
GlobalConfiguration.Setup().UseSqlServer();
var connection = builder.Configuration.GetConnectionString("ConnectionStrings:DefaultConnection");
app.UseAuthorization();
app.MapControllers();
app.ExtendBuilder(app.Services.GetRequiredService<ILoggerFactory>());
app.Use(async (context, next) =>
{
    string text = "X-Powered-By";
    var x_powered = context.Response.Headers[text];
    if (!string.IsNullOrEmpty(x_powered))
        context.Response.Headers.Remove(text);
    context.Response.Headers.Add("X-Xss-Protection", "1");
    context.Response.Headers.Add("Referrer-Policy", "nosniff");
    await next();
});
Uncovered code
app.Run();
